package ru.big.survey.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhonesTest {

    @Test
    void normalizes_like_v1() {
        assertThat(Phones.normalize("+7 (916) 123-45-67")).isEqualTo("79161234567");
        assertThat(Phones.normalize("8 916 123 45 67")).isEqualTo("79161234567");
        assertThat(Phones.normalize("9161234567")).isEqualTo("79161234567");
        assertThat(Phones.normalize("")).isEqualTo("");
        assertThat(Phones.normalize(null)).isEqualTo("");
    }

    @Test
    void validity_and_mask() {
        assertThat(Phones.isValid("79161234567")).isTrue();
        assertThat(Phones.isValid("7916123456")).isFalse();
        assertThat(Phones.isValid("89161234567")).isFalse();
        assertThat(Phones.mask("79161234567")).isEqualTo("+7 9** *** ** 67");
    }
}
