package ru.big.survey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.big.survey.config.SurveyProperties;
import tools.jackson.databind.ObjectMapper;

class ZvonokFlashCallClientTest {

    private final ZvonokFlashCallClient client = new ZvonokFlashCallClient(new SurveyProperties(), new Json(new ObjectMapper()));

    @Test
    void parses_pincode_keeping_leading_zeros() {
        assertThat(client.parseCode("{\"status\":\"ok\",\"data\":{\"pincode\":123,\"call_id\":\"x\"}}", "79161234567")).isEqualTo("0123");
        assertThat(client.parseCode("{\"status\":\"ok\",\"data\":{\"pincode\":\"0456\"}}", "79161234567")).isEqualTo("0456");
    }

    @Test
    void error_responses_become_api_exceptions() {
        assertThatThrownBy(() -> client.parseCode("{\"status\":\"error\",\"data\":\"phone is invalid\"}", "79161234567"))
                .isInstanceOf(ApiException.class).hasMessageContaining("номер");
        assertThatThrownBy(() -> client.parseCode("{\"status\":\"error\",\"data\":\"limit\"}", "79161234567"))
                .isInstanceOf(ApiException.class).hasMessageContaining("отклонил");
        assertThatThrownBy(() -> client.parseCode("{\"status\":\"ok\",\"data\":{}}", "79161234567"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Не получен код");
        assertThatThrownBy(() -> client.parseCode("<html>", "79161234567"))
                .isInstanceOf(ApiException.class);
    }
}
