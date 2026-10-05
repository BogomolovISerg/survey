package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Устройство (браузер), на котором телефон подтверждён кодом flash-call. Секрет живёт только в cookie
 * посетителя, в БД хранится его SHA-256. Записи создаёт и продлевает {@code PhoneDeviceRepository#upsert}.
 */
@Entity
@Table(name = "phone_device")
@IdClass(PhoneDevice.Key.class)
public class PhoneDevice {

    @Id
    @Column(length = 20)
    private String phone;

    @Id
    @Column(name = "device_hash", length = 64)
    private String deviceHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected PhoneDevice() {
    }

    public String getPhone() { return phone; }
    public String getDeviceHash() { return deviceHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public static class Key implements Serializable {
        private String phone;
        private String deviceHash;

        public Key() {
        }

        public Key(String phone, String deviceHash) {
            this.phone = phone;
            this.deviceHash = deviceHash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(phone, k.phone) && Objects.equals(deviceHash, k.deviceHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(phone, deviceHash);
        }
    }
}
