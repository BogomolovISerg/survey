package ru.big.survey.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.big.survey.config.SurveyProperties;

class TokenServiceTest {

    private TokenService service(Instant now, String secret) {
        SurveyProperties p = new SurveyProperties();
        p.getSecurity().setTokenSecret(secret);
        p.getSecurity().setVerificationTokenValid(Duration.ofMinutes(30));
        p.getSecurity().setGiftTokenValid(Duration.ofDays(30));
        TokenService s = new TokenService(p, Clock.fixed(now, ZoneOffset.UTC));
        s.init();
        return s;
    }

    @Test
    void verification_token_round_trip() {
        Instant now = Instant.parse("2026-10-21T10:00:00Z");
        TokenService s = service(now, "0123456789abcdef0123456789abcdef");
        String token = s.issueVerification("79161234567");
        assertThat(token).startsWith("v.79161234567.");
        assertThat(s.parseVerification(token)).isPresent();
        assertThat(s.parseVerification(token).get().phone()).isEqualTo("79161234567");
        assertThat(s.parseVerification(token).get().expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void tampered_or_expired_tokens_are_rejected() {
        Instant now = Instant.parse("2026-10-21T10:00:00Z");
        TokenService s = service(now, "0123456789abcdef0123456789abcdef");
        String token = s.issueVerification("79161234567");
        assertThat(s.parseVerification(token.replace("79161234567", "79161234568"))).isEmpty();
        assertThat(s.parseVerification(token + "x")).isEmpty();
        assertThat(s.parseVerification("v.1.2")).isEmpty();
        assertThat(s.parseVerification(null)).isEmpty();
        // другой секрет
        assertThat(service(now, "another-secret-another-secret-12").parseVerification(token)).isEmpty();
        // истёк
        assertThat(service(now.plus(Duration.ofMinutes(31)), "0123456789abcdef0123456789abcdef").parseVerification(token)).isEmpty();
        // тип не тот
        assertThat(s.parseGift(token)).isEmpty();
    }

    @Test
    void gift_token_round_trip_and_length() {
        Instant now = Instant.parse("2026-10-21T10:00:00Z");
        TokenService s = service(now, "0123456789abcdef0123456789abcdef");
        UUID id = UUID.fromString("5b0e7c9a-1234-4bcd-9ef0-1234567890ab");
        String token = s.issueGift(id);
        assertThat(token).startsWith("g.5b0e7c9a12344bcd9ef01234567890ab.");
        assertThat(token.length()).isLessThan(80);
        assertThat(s.parseGift(token)).isPresent();
        assertThat(s.parseGift(token).get().responseId()).isEqualTo(id);
    }

    @Test
    void code_hash_matches_v1_format() {
        // SHA-256("79161234567:1234") в верхнем hex, как ХешКодаПодтверждения в 1С
        assertThat(TokenService.codeHash("79161234567", "1234")).hasSize(64).matches("[0-9A-F]+");
        assertThat(TokenService.codeHash("79161234567", "1234")).isEqualTo(TokenService.codeHash("79161234567", "1234"));
        assertThat(TokenService.codeHash("79161234567", "1235")).isNotEqualTo(TokenService.codeHash("79161234567", "1234"));
    }
}
