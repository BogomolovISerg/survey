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
import ru.big.survey.domain.CallBudget;
import ru.big.survey.persistence.CallBudgetRepository;
import ru.big.survey.persistence.PhoneDeviceRepository;
import ru.big.survey.persistence.PhoneVerificationRepository;

/**
 * Подтверждение телефона flash-call (логика v1): код хранится как SHA-256(phone:code), TTL, лимит попыток,
 * повтор звонка не чаще resend-after, подтверждение действует verified-valid — повторный звонок не нужен.
 * После подтверждения выдаётся токен (TokenService), с ним посетитель отправляет анкету.
 * Повторно токен без звонка выдаётся только тому браузеру, где телефон уже подтверждали кодом: секрет устройства
 * лежит в cookie посетителя, а здесь сверяется его хеш (таблица phone_device). Знания номера для этого недостаточно.
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");

    /** Сутки, за которые уже записано предупреждение об исчерпании бюджета (чтобы не засорять журнал). */
    private volatile LocalDate budgetWarnedOn;

    private final PhoneVerificationRepository verifications;
    private final PhoneDeviceRepository devices;
    private final CallBudgetRepository budgets;
    private final FlashCallClient flashCall;
    private final TokenService tokens;
    private final SurveyProperties properties;
    private final Clock clock;

    public PhoneVerificationService(PhoneVerificationRepository verifications, PhoneDeviceRepository devices,
                                    CallBudgetRepository budgets, FlashCallClient flashCall, TokenService tokens,
                                    SurveyProperties properties, Clock clock) {
        this.verifications = verifications;
        this.devices = devices;
        this.budgets = budgets;
        this.flashCall = flashCall;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    /** status: called | already_verified | wait */
    public record CallResult(String status, String message, long ttlSeconds, int attempts, long retryAfterSeconds, String token) {}

    /** deviceSecret — новый секрет устройства: контроллер кладёт его в cookie браузера. */
    public record VerifyResult(String token, Instant validUntil, String deviceSecret) {}

    /**
     * @param deviceSecret значение cookie устройства (или null, если cookie нет). Если на этом устройстве телефон уже
     *                     подтверждали и срок не вышел — звонок не нужен, сразу выдаётся токен.
     */
    @Transactional
    public CallResult call(String rawPhone, String deviceSecret) {
        String phone = requireValidPhone(rawPhone);
        SurveyProperties.Verification cfg = properties.getVerification();
        Instant now = clock.instant();

        if (isKnownDevice(phone, deviceSecret, now)) {
            return new CallResult("already_verified", "Телефон уже подтверждён.", 0, 0, 0, tokens.issueVerification(phone));
        }
        LocalDate today = LocalDate.ofInstant(now, ZONE);
        // Общий суточный бюджет звонков: предел расходов, даже если заказывают с разных номеров и адресов. Проверка без
        // блокировок (строку счётчика не держим на время обращения к провайдеру); перелёт возможен лишь на число
        // одновременно идущих запросов
        int budget = cfg.getMaxCallsPerDay();
        if (budget > 0 && budgets.findById(today).map(CallBudget::getCalls).orElse(0) >= budget) {
            if (!today.equals(budgetWarnedOn)) {
                budgetWarnedOn = today;
                log.warn("Исчерпан суточный предел звонков ({}), новые звонки не заказываются до завтра", budget);
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "busy",
                    "Подтверждение телефона временно недоступно. Попробуйте позже или обратитесь к промоутеру.");
        }
        // Строка телефона блокируется до конца транзакции: параллельные заказы на один номер выстраиваются в очередь
        // и видят результат друг друга, поэтому окно повтора и суточный лимит нельзя обойти запросами «в один момент»
        verifications.insertIfAbsent(phone, now);
        PhoneVerification v = verifications.findForUpdate(phone)
                .orElseThrow(() -> new IllegalStateException("Строка проверки телефона не создана"));
        if (v.getLastCallAt() != null) {
            long since = now.getEpochSecond() - v.getLastCallAt().getEpochSecond();
            long wait = cfg.getResendAfter().toSeconds() - since;
            if (wait > 0 && v.codeIsAlive(now)) {
                return new CallResult("wait", "Звонок уже выполняется. Дождитесь входящего вызова.",
                        Math.max(0, v.getExpiresAt().getEpochSecond() - now.getEpochSecond()),
                        Math.max(0, cfg.getMaxAttempts() - v.getAttempts()), wait, null);
            }
        }
        if (v.callsOn(today) >= cfg.getMaxCallsPerPhonePerDay()) {
            throw ApiException.tooMany("Превышено число звонков на этот номер за сутки. Попробуйте завтра или обратитесь к промоутеру.",
                    Map.of("retryAfter", 3600));
        }

        String code = flashCall.call(phone);
        v.newCode(TokenService.codeHash(phone, code), now, now.plus(cfg.getCodeTtl()), today);
        verifications.save(v);
        budgets.increment(today);   // только после успешного ответа провайдера; при ошибке транзакция откатывается целиком
        log.info("Flash-call заказан для {}", Phones.mask(phone));
        return new CallResult("called", "Ожидайте входящий звонок и введите последние 4 цифры номера.",
                cfg.getCodeTtl().toSeconds(), cfg.getMaxAttempts(), cfg.getResendAfter().toSeconds(), null);
    }

    /**
     * noRollbackFor: неверный код сообщается исключением, но занятая попытка должна сохраниться. Иначе Spring откатит
     * транзакцию вместе со счётчиком, и перебор кода станет безлимитным.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public VerifyResult verify(String rawPhone, String rawCode) {
        String phone = requireValidPhone(rawPhone);
        String code = rawCode == null ? "" : rawCode.replaceAll("\\D", "");
        if (code.length() < 3 || code.length() > 6) {
            throw ApiException.badRequest("code", "Введите последние 4 цифры входящего номера.");
        }
        SurveyProperties.Verification cfg = properties.getVerification();
        Instant now = clock.instant();
        // Попытка занимается атомарно ДО сравнения кода: параллельные запросы не обходят лимит, а токен выдаётся
        // только за верный код (признак «телефон уже подтверждён» доступа не даёт)
        if (verifications.claimAttempt(phone, now, cfg.getMaxAttempts()) == 0) {
            PhoneVerification stale = verifications.findById(phone)
                    .orElseThrow(() -> ApiException.badRequest("no_call", "Сначала закажите звонок."));
            if (!stale.codeIsAlive(now)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "expired", "Код устарел. Закажите звонок ещё раз.");
            }
            throw ApiException.tooMany("Превышено число попыток. Закажите звонок ещё раз.",
                    Map.of("retryAfter", Math.max(0, stale.getExpiresAt().getEpochSecond() - now.getEpochSecond())));
        }
        PhoneVerification v = verifications.findById(phone)
                .orElseThrow(() -> ApiException.badRequest("no_call", "Сначала закажите звонок."));
        // Сравниваем последние 4 цифры (посетитель может ввести номер целиком)
        String tail = code.length() > 4 ? code.substring(code.length() - 4) : code;
        boolean ok = TokenService.codeHash(phone, tail).equals(v.getCodeHash())
                || TokenService.codeHash(phone, code).equals(v.getCodeHash());
        if (!ok) {
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
        // Новый секрет на каждое подтверждение: значение, пришедшее в cookie, не переиспользуем (защита от подмены cookie)
        String deviceSecret = DeviceSecrets.generate();
        devices.upsert(phone, DeviceSecrets.hash(deviceSecret), now, until);
        log.info("Телефон {} подтверждён", Phones.mask(phone));
        return new VerifyResult(tokens.issueVerification(phone), until, deviceSecret);
    }

    private boolean isKnownDevice(String phone, String deviceSecret, Instant now) {
        return DeviceSecrets.isWellFormed(deviceSecret)
                && devices.existsByPhoneAndDeviceHashAndExpiresAtAfter(phone, DeviceSecrets.hash(deviceSecret), now);
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
        Instant now = clock.instant();
        int removed = verifications.deleteExpired(now.minusSeconds(24 * 3600));
        if (removed > 0) {
            log.info("Удалено истёкших проверок телефонов: {}", removed);
        }
        budgets.deleteBefore(LocalDate.ofInstant(now, ZONE).minusDays(7));
        int removedDevices = devices.deleteExpired(now);
        if (removedDevices > 0) {
            log.info("Удалено истёкших устройств посетителей: {}", removedDevices);
        }
    }
}
