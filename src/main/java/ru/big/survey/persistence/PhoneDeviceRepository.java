package ru.big.survey.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.big.survey.domain.PhoneDevice;

public interface PhoneDeviceRepository extends JpaRepository<PhoneDevice, PhoneDevice.Key> {

    /** Подтверждён ли телефон на этом устройстве к моменту {@code now}. */
    boolean existsByPhoneAndDeviceHashAndExpiresAtAfter(String phone, String deviceHash, Instant now);

    /** Регистрация устройства; при повторной проверке на том же устройстве срок продлевается. */
    @Modifying
    @Query(value = """
            insert into phone_device (phone, device_hash, created_at, expires_at)
            values (:phone, :deviceHash, :now, :expiresAt)
            on conflict (phone, device_hash) do update set expires_at = excluded.expires_at
            """, nativeQuery = true)
    int upsert(@Param("phone") String phone, @Param("deviceHash") String deviceHash,
               @Param("now") Instant now, @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query("delete from PhoneDevice d where d.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
