package ru.big.survey.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeviceSecretsTest {

    @Test
    void generated_secret_is_well_formed_and_unique() {
        String a = DeviceSecrets.generate();
        String b = DeviceSecrets.generate();
        assertThat(DeviceSecrets.isWellFormed(a)).isTrue();
        assertThat(a).hasSize(43);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hash_is_stable_sha256_hex() {
        String secret = DeviceSecrets.generate();
        assertThat(DeviceSecrets.hash(secret)).hasSize(64);
        assertThat(DeviceSecrets.hash(secret)).isEqualTo(DeviceSecrets.hash(secret));
        assertThat(DeviceSecrets.hash(secret)).isNotEqualTo(DeviceSecrets.hash(DeviceSecrets.generate()));
        // SHA-256("abc")
        assertThat(DeviceSecrets.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void foreign_values_are_not_trusted() {
        assertThat(DeviceSecrets.isWellFormed(null)).isFalse();
        assertThat(DeviceSecrets.isWellFormed("")).isFalse();
        assertThat(DeviceSecrets.isWellFormed("abc")).isFalse();
        assertThat(DeviceSecrets.isWellFormed("a".repeat(44))).isFalse();
        assertThat(DeviceSecrets.isWellFormed("a".repeat(42) + "=")).isFalse();
        assertThat(DeviceSecrets.isWellFormed("a".repeat(42) + ";")).isFalse();
    }
}
