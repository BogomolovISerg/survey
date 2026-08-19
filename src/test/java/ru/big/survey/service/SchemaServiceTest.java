package ru.big.survey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SchemaServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemaService service = new SchemaService(new Json(mapper));

    private static final String SCHEMA = """
            {
              "enums": {"Города": ["Москва", "Другой..."]},
              "components": {
                "Идентификация": {"conditions": "", "required": ["Имя", "Телефон", "Город"],
                  "data": [
                    {"Имя": {"label": "Имя", "element": "inputbox"}},
                    {"Телефон": {"label": "Телефон", "element": "inputbox", "mask": "phone"}},
                    {"Город": {"label": "Город", "element": "radio", "type": "enum", "values": "Города"}},
                    {"Метро": {"conditions": "Город == \\"Москва\\"", "label": "Метро", "element": "inputbox"}},
                    {"Город": {"conditions": "Город != \\"Москва\\"", "label": "Город", "element": "inputbox"}}
                  ]},
                "Интерес": {"conditions": "Город == \\"Москва\\"", "required": ["Интерес"],
                  "data": [{"Интерес": {"element": "radio", "type": "enum", "values": "Интерес"}}]},
                "Вложенный": {"required": ["Бренд"], "data": [{"Бренд": {"element": "inputbox"}}]},
                "Обёртка": {"data": ["{Вложенный}"]}
              },
              "schema": ["{Идентификация}", "{Интерес}", "{Обёртка}"],
              "style": {},
              "output": {"Имя": "", "Телефон": "", "Город": "", "Метро": "", "Интерес": "", "Бренд": ""}
            }
            """;

    @Test
    void valid_schema_passes_and_checksum_is_stable() {
        JsonNode s = mapper.readTree(SCHEMA);
        service.validate(s);
        String c1 = service.checksum(s);
        assertThat(c1).hasSize(64);
        // другой порядок ключей — та же контрольная сумма
        assertThat(service.checksum(mapper.readTree("{\"b\":1,\"a\":{\"y\":2,\"x\":1}}")))
                .isEqualTo(service.checksum(mapper.readTree("{\"a\":{\"x\":1,\"y\":2},\"b\":1}")));
        // изменение значения — другая
        assertThat(service.checksum(mapper.readTree("{\"a\":1}"))).isNotEqualTo(service.checksum(mapper.readTree("{\"a\":2}")));
        assertThat(service.checksum(mapper.readTree(SCHEMA))).isEqualTo(c1);
    }

    @Test
    void invalid_schemas_are_rejected() {
        assertThatThrownBy(() -> service.validate(mapper.readTree("{\"components\":{}, \"schema\":[]}")))
                .isInstanceOf(ApiException.class).hasMessageContaining("components");
        assertThatThrownBy(() -> service.validate(mapper.readTree("{\"components\":{\"A\":{}}, \"schema\":[\"{B}\"]}")))
                .isInstanceOf(ApiException.class).hasMessageContaining("неизвестный компонент");
        assertThatThrownBy(() -> service.validate(mapper.readTree("{\"components\":{\"A\":{\"data\":[\"{X}\"]}}, \"schema\":[\"{A}\"]}")))
                .isInstanceOf(ApiException.class).hasMessageContaining("X");
    }

    @Test
    void required_fields_respect_visibility() {
        JsonNode s = mapper.readTree(SCHEMA);
        // Москва: Интерес обязателен и виден; Метро не обязателен; Бренд обязателен через вложенный компонент
        assertThat(service.missingRequired(s, mapper.readTree("{\"Имя\":\"Анна\",\"Телефон\":\"79161234567\",\"Город\":\"Москва\"}")))
                .containsExactly("Интерес", "Бренд");
        // Не Москва: компонент Интерес скрыт целиком → не обязателен
        assertThat(service.missingRequired(s, mapper.readTree("{\"Имя\":\"Анна\",\"Телефон\":\"79161234567\",\"Город\":\"Тула\",\"Бренд\":\"X\"}")))
                .isEmpty();
        // Пустые строки и пустые массивы считаются незаполненными
        assertThat(service.missingRequired(s, mapper.readTree("{\"Имя\":\"  \",\"Телефон\":\"\",\"Город\":[]}")))
                .containsExactly("Имя", "Телефон", "Город", "Бренд");
    }

    @Test
    void ref_name_strips_braces() {
        assertThat(SchemaService.refName(mapper.getNodeFactory().textNode("{Идентификация}"))).isEqualTo("Идентификация");
        assertThat(SchemaService.refName(mapper.getNodeFactory().textNode(" { Comp_1 } "))).isEqualTo("Comp_1");
    }
}
