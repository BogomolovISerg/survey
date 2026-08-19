package ru.big.survey.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.config.SurveyProperties;
import ru.big.survey.domain.PhoneVerification;
import ru.big.survey.persistence.PhoneVerificationRepository;

/**
 * Подтверждение телефона flash-call (логика v1): код хранится как SHA-256(phone:code), TTL, лимит попыток,
 * повтор звонка не чаще resend-after, подтверждение действует verified-valid — повторный звонок не нужен.
 * После подтверждения выдаётся токен (TokenService), с ним посетитель отправляет анкету.
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    private final PhoneVerificationRepository verifications;
    private final FlashCallClient flashCall;
    private final TokenService tokens;
    private final SurveyProperties properties;
    private final Clock clock;

    public PhoneVerificationService(PhoneVerificationRepository verifications, FlashCallClient flashCall,
                                    TokenService tokens, SurveyProperties properties, Clock clock) {
        this.verifications = verifications;
        this.flashCall = flashCall;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    /** status: called | already_verified | wait */
    public record CallResult(String status, String message, long ttlSeconds, int attempts, long retryAfterSeconds, String token) {}

    public record VerifyResult(String token, Instant validUntil) {}

    @Transactional
    public CallResult call(String rawPhone) {
        String phone = requireValidPhone(rawPhone);
        SurveyProperties.Verification cfg = properties.getVerification();
        Instant now = clock.instant();
        PhoneVerification v = verifications.findById(phone).orElseGet(() -> PhoneVerification.create(phone, now));

        if (v.isVerifiedAt(now)) {
            return new CallResult("already_verified", "Телефон уже подтверждён.", 0, 0, 0, tokens.issueVerification(phone));
        }
        if (v.getLastCallAt() != null) {
            long since = now.getEpochSecond() - v.getLastCallAt().getEpochSecond();
            long wait = cfg.getResendAfter().toSeconds() - since;
            if (wait > 0 && v.codeIsAlive(now)) {
                return new CallResult("wait", "Звонок уже выполняется. Дождитесь входящего вызова.",
                        Math.max(0, v.getExpiresAt().getEpochSecond() - now.getEpochSecond()),
                        Math.max(0, cfg.getMaxAttempts() - v.getAttempts()), wait, null);
            }
        }
        LocalDate today = LocalDate.ofInstant(now, ZONE);
        if (v.callsOn(today) >= cfg.getMaxCallsPerPhonePerDay()) {
            throw ApiException.tooMany("Превышено число звонков на этот номер за сутки. Попробуйте завтра или обратитесь к промоутеру.",
                    Map.of("retryAfter", 3600));
        }

        String code = flashCall.call(phone);
        v.newCode(TokenService.codeHash(phone, code), now, now.plus(cfg.getCodeTtl()), today);
        verifications.save(v);
        log.info("Flash-call заказан для {}", Phones.mask(phone));
        return new CallResult("called", "Ожидайте входящий звонок и введите последние 4 цифры номера.",
                cfg.getCodeTtl().toSeconds(), cfg.getMaxAttempts(), cfg.getResendAfter().toSeconds(), null);
    }

    @Transactional
    public VerifyResult verify(String rawPhone, String rawCode) {
        String phone = requireValidPhone(rawPhone);
        String code = rawCode == null ? "" : rawCode.replaceAll("\\D", "");
        if (code.length() < 3 || code.length() > 6) {
            throw ApiException.badRequest("code", "Введите последние 4 цифры входящего номера.");
        }
        SurveyProperties.Verification cfg = properties.getVerification();
        Instant now = clock.instant();
        PhoneVerification v = verifications.findById(phone)
                .orElseThrow(() -> ApiException.badRequest("no_call", "Сначала закажите звонок."));
        if (v.isVerifiedAt(now)) {
            return new VerifyResult(tokens.issueVerification(phone), v.getExpiresAt());
        }
        if (!v.codeIsAlive(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "expired", "Код устарел. Закажите звонок ещё раз.");
        }
        if (v.getAttempts() >= cfg.getMaxAttempts()) {
            throw ApiException.tooMany("Превышено число попыток. Закажите звонок ещё раз.",
                    Map.of("retryAfter", Math.max(0, v.getExpiresAt().getEpochSecond() - now.getEpochSecond())));
        }
        v.registerAttempt();
        // Сравниваем последние 4 цифры (посетитель может ввести номер целиком)
        String tail = code.length() > 4 ? code.substring(code.length() - 4) : code;
        boolean ok = TokenService.codeHash(phone, tail).equals(v.getCodeHash())
                || TokenService.codeHash(phone, code).equals(v.getCodeHash());
        if (!ok) {
            verifications.save(v);
            int left = Math.max(0, cfg.getMaxAttempts() - v.getAttempts());
            if (left == 0) {
                throw ApiException.tooMany("Код неверный, попытки исчерпаны. Закажите звонок ещё раз.", Map.of("attemptsLeft", 0));
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "wrong_code", "Код неверный. Осталось попыток: " + left + ".",
                    Map.of("attemptsLeft", left));
        }
        Instant until = now.plus(cfg.getVerifiedValid());
        v.markVerified(now, until);
        verifications.save(v);
        log.info("Телефон {} подтверждён", Phones.mask(phone));
        return new VerifyResult(tokens.issueVerification(phone), until);
    }

    /** Телефон из токена верификации; 403 если токен невалиден или истёк. */
    public String phoneFromToken(String token) {
        return tokens.parseVerification(token)
                .map(TokenService.Verified::phone)
                .orElseThrow(() -> ApiException.forbidden("Подтверждение телефона не найдено или устарело. Подтвердите номер ещё раз."));
    }

    public static String requireValidPhone(String raw) {
        String phone = Phones.normalize(raw);
        if (!Phones.isValid(phone)) {
            throw ApiException.badRequest("phone", "Неверный формат номера телефона.");
        }
        return phone;
    }

    /** Чистка истёкших записей (коды и просроченные подтверждения). */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    @Transactional
    public void purgeExpired() {
        int removed = verifications.deleteExpired(clock.instant().minusSeconds(24 * 3600));
        if (removed > 0) {
            log.info("Удалено истёкших проверок телефонов: {}", removed);
        }
    }
}
