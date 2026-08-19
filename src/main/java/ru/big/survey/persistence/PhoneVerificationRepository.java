package ru.big.survey.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.big.survey.domain.PhoneVerification;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, String> {

    @Modifying
    @Query("delete from PhoneVerification v where v.expiresAt is not null and v.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
