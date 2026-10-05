package ru.big.survey.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.big.survey.config.SurveyProperties;
import ru.big.survey.service.MarkingCheckClient.Status;
import tools.jackson.databind.ObjectMapper;

class CrptMarkingCheckClientTest {

    private final CrptMarkingCheckClient client = new CrptMarkingCheckClient(new SurveyProperties(), new Json(new ObjectMapper()));
    private static final String CODE = "0104630274256559215MPXYg93DKiY";

    private static String wrap(String cisInfo) {
        return "[{\"cisInfo\":" + cisInfo + "}]";
    }

    @Test
    void introduced_code_is_in_circulation() {
        assertThat(client.parse(wrap("{\"cis\":\"x\",\"status\":\"INTRODUCED\",\"markWithdraw\":false}"), CODE).status())
                .isEqualTo(Status.IN_CIRCULATION);
    }

    @Test
    void non_introduced_statuses_are_rejected() {
        assertThat(client.parse(wrap("{\"status\":\"EMITTED\"}"), CODE).detail()).contains("не введён");
        assertThat(client.parse(wrap("{\"status\":\"APPLIED\"}"), CODE).status()).isEqualTo(Status.REJECTED);
        assertThat(client.parse(wrap("{\"status\":\"RETIRED\"}"), CODE).detail()).contains("выведен");
        assertThat(client.parse(wrap("{\"status\":\"WRITTEN_OFF\"}"), CODE).detail()).contains("выведен");
        assertThat(client.parse(wrap("{\"status\":\"DISAGGREGATED\"}"), CODE).status()).isEqualTo(Status.REJECTED);
        assertThat(client.parse("[{\"errorMessage\":\"не найден\"}]", CODE).status()).isEqualTo(Status.REJECTED);
    }

    @Test
    void unparseable_or_empty_body_is_unknown() {
        assertThat(client.parse("<html>", CODE).status()).isEqualTo(Status.UNKNOWN);
        assertThat(client.parse("[]", CODE).status()).isEqualTo(Status.UNKNOWN);
        assertThat(client.parse("[{\"cisInfo\":{}}]", CODE).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void cis_key_cuts_crypto_tail() {
        // короткий формат: серийник 6 символов + группа 93
        assertThat(CrptMarkingCheckClient.cisKey("0104630274258287215obOoX93YJK1")).isEqualTo("0104630274258287215obOoX");
        // уже без хвоста — как есть
        assertThat(CrptMarkingCheckClient.cisKey("0104630274258287215obOoX")).isEqualTo("0104630274258287215obOoX");
        // длинный формат: серийник 13 символов, хвост 93 после 31-го символа
        assertThat(CrptMarkingCheckClient.cisKey("010463027425828721AbCdEf12345Xy93Qz0=")).isEqualTo("010463027425828721AbCdEf12345Xy");
        // разделитель GS уцелел — режем по нему
        assertThat(CrptMarkingCheckClient.cisKey("0104630274258287215obOoX\u001d93YJK1")).isEqualTo("0104630274258287215obOoX");
        // незнакомый формат не трогаем
        assertThat(CrptMarkingCheckClient.cisKey("abc")).isEqualTo("abc");
    }

    @Test
    void jwt_expiry_is_extracted() {
        // payload: {"exp":1787842291}
        String jwt = "eyJhbGciOiJIUzI1NiJ9." + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"foo\":1,\"exp\":1787842291}".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".sig";
        assertThat(CrptMarkingCheckClient.jwtExpiry(jwt)).isEqualTo(java.time.Instant.ofEpochSecond(1787842291L));
        assertThat(CrptMarkingCheckClient.jwtExpiry("not-a-jwt")).isNull();
    }

    @Test
    void delivered_token_has_priority_and_expires() {
        java.time.Instant now = java.time.Instant.parse("2026-08-26T10:00:00Z");
        assertThat(client.activeToken(now)).isEmpty(); // ни конфиг, ни доставка
        client.acceptToken("t1", java.time.Instant.parse("2026-08-26T12:00:00Z"));
        assertThat(client.activeToken(now)).isEqualTo("t1");
        assertThat(client.activeToken(java.time.Instant.parse("2026-08-26T13:00:00Z"))).isEmpty(); // истёк
    }
}
