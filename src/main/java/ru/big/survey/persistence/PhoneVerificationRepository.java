package ru.big.survey.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.big.survey.domain.PhoneVerification;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, String> {

    @Modifying
    @Query("delete from PhoneVerification v where v.expiresAt is not null and v.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);

    /** Создаёт пустую строку телефона, если её ещё нет (параллельные вставки безопасны). */
    @Modifying
    @Query(value = """
            insert into phone_verification (phone, attempts, verified, created_at, calls_today)
            values (:phone, 0, false, :now, 0)
            on conflict (phone) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("phone") String phone, @Param("now") Instant now);

    /**
     * Строка телефона под блокировкой до конца транзакции: параллельные заказы звонка на один номер выстраиваются
     * в очередь и видят результат друг друга (иначе окно повтора и суточный лимит обходятся запросами «в один момент»).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PhoneVerification v where v.phone = :phone")
    Optional<PhoneVerification> findForUpdate(@Param("phone") String phone);

    /**
     * Атомарно занимает одну попытку ввода кода одним UPDATE: условие {@code attempts < :max} проверяется под
     * блокировкой строки, поэтому параллельные запросы не могут проверить больше {@code max} кодов.
     * Возвращает 1, если попытка занята и код можно сравнивать, и 0, если кода нет, он истёк, уже подтверждён или
     * попытки исчерпаны. Контекст сбрасывается, чтобы следующее чтение увидело новое значение счётчика.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update PhoneVerification v set v.attempts = v.attempts + 1
            where v.phone = :phone and v.verified = false and v.codeHash is not null
              and v.expiresAt > :now and v.attempts < :max
            """)
    int claimAttempt(@Param("phone") String phone, @Param("now") Instant now, @Param("max") int max);
}
