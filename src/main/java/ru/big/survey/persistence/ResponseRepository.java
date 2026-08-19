package ru.big.survey.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.big.survey.domain.Response;

public interface ResponseRepository extends JpaRepository<Response, UUID> {

    Optional<Response> findByEventIdAndPhone(UUID eventId, String phone);

    Optional<Response> findByEventIdAndGiftCode(UUID eventId, String giftCode);

    boolean existsByEventIdAndGiftCode(UUID eventId, String giftCode);

    @Query("""
            select r from Response r
            where r.eventId = :eventId and r.changeSeq > :after
            order by r.changeSeq
            """)
    List<Response> findChangedAfter(@Param("eventId") UUID eventId, @Param("after") long after, Pageable pageable);

    long countByEventId(UUID eventId);

    long countByEventIdAndSubmittedAtAfter(UUID eventId, Instant since);

    long countByEventIdAndGiftAwardedTrue(UUID eventId);

    long countByEventIdAndChangeSeqGreaterThan(UUID eventId, long seq);

    List<Response> findTop10ByEventIdOrderBySubmittedAtDesc(UUID eventId);

    Page<Response> findAllByEventIdOrderBySubmittedAtDesc(UUID eventId, Pageable pageable);

    List<Response> findAllByEventIdOrderBySubmittedAtAsc(UUID eventId);

    @Query(value = "select nextval('response_change_seq')", nativeQuery = true)
    long nextChangeSeq();
}
