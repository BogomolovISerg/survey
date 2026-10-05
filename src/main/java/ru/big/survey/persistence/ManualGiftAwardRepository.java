package ru.big.survey.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.big.survey.domain.ManualGiftAward;

public interface ManualGiftAwardRepository extends JpaRepository<ManualGiftAward, UUID> {
    long countByEventId(UUID eventId);

    List<ManualGiftAward> findTop10ByEventIdOrderByAtDesc(UUID eventId);

    long countByEventIdAndChangeSeqGreaterThan(UUID eventId, long seq);

    @Query("""
            select g from ManualGiftAward g
            where g.eventId = :eventId and g.changeSeq > :after
            order by g.changeSeq
            """)
    List<ManualGiftAward> findChangedAfter(@Param("eventId") UUID eventId, @Param("after") long after, Pageable pageable);

    /** Уникальный id исключает повторную выдачу при одновременных повторах одного запроса. */
    @Modifying
    @Query(value = """
            insert into manual_gift_award (id, event_id, at, by_user, item_codes)
            values (:id, :eventId, :at, :byUser, cast(:itemCodes as jsonb))
            on conflict (id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId,
                       @Param("at") Instant at, @Param("byUser") String byUser,
                       @Param("itemCodes") String itemCodes);
}
