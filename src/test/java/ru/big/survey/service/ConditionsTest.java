package ru.big.survey.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ConditionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private boolean eval(String condition, String answersJson) {
        JsonNode cond = mapper.getNodeFactory().textNode(condition);
        return Conditions.evaluate(cond, mapper.readTree(answersJson));
    }

    @Test
    void empty_condition_is_visible() {
        assertThat(Conditions.evaluate(null, mapper.readTree("{}"))).isTrue();
        assertThat(eval("", "{}")).isTrue();
        assertThat(eval("   ", "{}")).isTrue();
    }

    @Test
    void equality_on_strings() {
        assertThat(eval("Город == \"Москва\"", "{\"Город\":\"Москва\"}")).isTrue();
        assertThat(eval("Город == 'Москва'", "{\"Город\":\"Питер\"}")).isFalse();
        assertThat(eval("Город != \"Москва\"", "{\"Город\":\"Питер\"}")).isTrue();
        assertThat(eval("Город != \"Москва\"", "{}")).isTrue();
        assertThat(eval("Город === \"Москва\"", "{\"Город\":\"Москва\"}")).isTrue();
    }

    @Test
    void logic_and_parentheses() {
        String a = "{\"Статус\":\"Работаю в салоне\",\"Интерес\":\"по работе\"}";
        assertThat(eval("Статус == \"Работаю в салоне\" || Статус == \"Администратор\"", a)).isTrue();
        assertThat(eval("Статус == \"Другое\" && Интерес == \"по работе\"", a)).isFalse();
        assertThat(eval("(Статус == \"Другое\" || Интерес == \"по работе\") && !Пусто", a)).isTrue();
        assertThat(eval("!(Интерес == \"по работе\")", a)).isFalse();
    }

    @Test
    void arrays_mean_contains() {
        assertThat(eval("Бренды == \"A\"", "{\"Бренды\":[\"B\",\"A\"]}")).isTrue();
        assertThat(eval("Бренды == \"C\"", "{\"Бренды\":[\"B\",\"A\"]}")).isFalse();
        assertThat(eval("Бренды", "{\"Бренды\":[]}")).isFalse();
        assertThat(eval("Бренды", "{\"Бренды\":[\"x\"]}")).isTrue();
    }

    @Test
    void booleans_and_numbers() {
        assertThat(eval("Согласие == true", "{\"Согласие\":true}")).isTrue();
        assertThat(eval("Согласие == true", "{\"Согласие\":\"true\"}")).isTrue();
        assertThat(eval("Согласие", "{\"Согласие\":\"false\"}")).isFalse();
        assertThat(eval("Возраст >= 18", "{\"Возраст\":\"20\"}")).isTrue();
        assertThat(eval("Возраст < 18", "{\"Возраст\":\"20,5\"}")).isFalse();
        assertThat(eval("Возраст > 1", "{\"Возраст\":\"abc\"}")).isFalse();
        assertThat(eval("Возраст == 20", "{\"Возраст\":20}")).isTrue();
    }

    @Test
    void syntax_error_means_visible() {
        assertThat(eval("Город == ", "{}")).isTrue();
        assertThat(eval("Город ==== \"x\"", "{}")).isTrue();
        assertThat(eval("Город == \"незакрытая", "{}")).isTrue();
    }

    @Test
    void structured_form() {
        String cond = "{\"any\":[{\"field\":\"Город\",\"op\":\"==\",\"value\":\"Москва\"},{\"field\":\"Город\",\"value\":\"Казань\"}]}";
        assertThat(Conditions.evaluate(mapper.readTree(cond), mapper.readTree("{\"Город\":\"Казань\"}"))).isTrue();
        assertThat(Conditions.evaluate(mapper.readTree(cond), mapper.readTree("{\"Город\":\"Тула\"}"))).isFalse();
        String not = "{\"not\":{\"all\":[{\"field\":\"A\",\"value\":\"1\"},{\"field\":\"B\",\"value\":\"2\"}]}}";
        assertThat(Conditions.evaluate(mapper.readTree(not), mapper.readTree("{\"A\":\"1\",\"B\":\"2\"}"))).isFalse();
        assertThat(Conditions.evaluate(mapper.readTree(not), mapper.readTree("{\"A\":\"1\"}"))).isTrue();
    }

    @Test
    void item_visibility_uses_conditions_property() {
        JsonNode item = mapper.readTree("{\"label\":\"Метро\",\"conditions\":\"Город == \\\"Москва\\\"\"}");
        assertThat(Conditions.isVisible(item, mapper.readTree("{\"Город\":\"Москва\"}"))).isTrue();
        assertThat(Conditions.isVisible(item, mapper.readTree("{\"Город\":\"Тверь\"}"))).isFalse();
        assertThat(Conditions.isVisible(mapper.readTree("{\"label\":\"x\"}"), mapper.readTree("{}"))).isTrue();
    }
}
