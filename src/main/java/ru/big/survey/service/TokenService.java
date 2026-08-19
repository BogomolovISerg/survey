package ru.big.survey.service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.big.survey.config.SurveyProperties;

/**
 * Подписанные токены без состояния (HMAC-SHA256, подпись усечена до 128 бит, base64url):
 *   v.<телефон>.<exp>.<sig>   — телефон подтверждён, можно отправлять ответ / смотреть подарок;
 *   g.<responseId без дефисов>.<exp>.<sig> — QR подарка для панели стенда.
 * Секрет — survey.security.token-secret; при пустом секрете генерируется случайный на время работы процесса
 * (токены не переживут рестарт) и пишется предупреждение.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final SurveyProperties properties;
    private final Clock clock;
    private byte[] secret;

    public TokenService(SurveyProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        String configured = properties.getSecurity().getTokenSecret();
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new java.security.SecureRandom().nextBytes(random);
            secret = random;
            log.warn("survey.security.token-secret не задан — используется случайный секрет на время работы процесса. "
                    + "Задайте секрет в конфигурации для боевого контура.");
        } else {
            if (configured.length() < 32) {
                log.warn("survey.security.token-secret короче 32 символов — используйте более длинный секрет");
            }
            secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    public record Verified(String phone, Instant expiresAt) {}
    public record Gift(UUID responseId, Instant expiresAt) {}

    public String issueVerification(String phone) {
        return issue("v", phone, properties.getSecurity().getVerificationTokenValid());
    }

    public Optional<Verified> parseVerification(String token) {
        return parse(token, "v").map(p -> new Verified(p.payload(), p.expiresAt()));
    }

    public String issueGift(UUID responseId) {
        return issue("g", responseId.toString().replace("-", ""), properties.getSecurity().getGiftTokenValid());
    }

    public Optional<Gift> parseGift(String token) {
        return parse(token, "g").flatMap(p -> {
            try {
                String hex = p.payload();
                if (hex.length() != 32) {
                    return Optional.empty();
                }
                String withDashes = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                        + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
                return Optional.of(new Gift(UUID.fromString(withDashes), p.expiresAt()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        });
    }

    /** SHA-256(phone:code) в hex — как в v1 (РС бигПроверкиТелефонов). */
    public static String codeHash(String phone, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().withUpperCase().formatHex(digest.digest((phone + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- внутреннее ----

    private record Parsed(String payload, Instant expiresAt) {}

    private String issue(String kind, String payload, Duration valid) {
        long exp = clock.instant().plus(valid).getEpochSecond();
        String body = kind + "." + payload + "." + exp;
        return body + "." + sign(body);
    }

    private Optional<Parsed> parse(String token, String kind) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 4 || !parts[0].equals(kind)) {
            return Optional.empty();
        }
        String body = parts[0] + "." + parts[1] + "." + parts[2];
        byte[] expected;
        byte[] actual;
        try {
            expected = B64D.decode(sign(body));
            actual = B64D.decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            return Optional.empty();
        }
        long exp;
        try {
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        Instant expiresAt = Instant.ofEpochSecond(exp);
        if (!expiresAt.isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(parts[1], expiresAt));
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] full = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[16];
            System.arraycopy(full, 0, truncated, 0, 16);
            return B64.encodeToString(truncated);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
