package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** Состояние подтверждения телефона flash-call: хеш кода, попытки, факт подтверждения. */
@Entity
@Table(name = "phone_verification")
public class PhoneVerification {

    @Id
    @Column(length = 20)
    private String phone;

    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private boolean verified;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_call_at")
    private Instant lastCallAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    /** Для неподтверждённой записи — истечение кода; для подтверждённой — истечение подтверждения. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "calls_day")
    private LocalDate callsDay;

    @Column(name = "calls_today", nullable = false)
    private int callsToday;

    protected PhoneVerification() {
    }

    public static PhoneVerification create(String phone, Instant now) {
        PhoneVerification v = new PhoneVerification();
        v.phone = phone;
        v.createdAt = now;
        return v;
    }

    /** Новый код после звонка: сброс попыток и признака подтверждения. */
    public void newCode(String codeHash, Instant now, Instant codeExpiresAt, LocalDate today) {
        this.codeHash = codeHash;
        this.attempts = 0;
        this.verified = false;
        this.verifiedAt = null;
        this.lastCallAt = now;
        this.expiresAt = codeExpiresAt;
        if (!today.equals(callsDay)) {
            callsDay = today;
            callsToday = 0;
        }
        callsToday++;
    }

    public void registerAttempt() {
        this.attempts++;
    }

    public void markVerified(Instant now, Instant verifiedUntil) {
        this.verified = true;
        this.verifiedAt = now;
        this.expiresAt = verifiedUntil;
        this.codeHash = null;
    }

    public boolean isVerifiedAt(Instant now) {
        return verified && expiresAt != null && expiresAt.isAfter(now);
    }

    public boolean codeIsAlive(Instant now) {
        return !verified && codeHash != null && expiresAt != null && expiresAt.isAfter(now);
    }

    public int callsOn(LocalDate today) {
        return today.equals(callsDay) ? callsToday : 0;
    }

    public String getPhone() { return phone; }
    public String getCodeHash() { return codeHash; }
    public int getAttempts() { return attempts; }
    public boolean isVerified() { return verified; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastCallAt() { return lastCallAt; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
