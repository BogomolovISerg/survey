package ru.big.survey.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.big.survey.domain.GiftItem;

public interface GiftItemRepository extends JpaRepository<GiftItem, UUID> {

    List<GiftItem> findAllByResponseIdOrderByAddedAt(UUID responseId);

    List<GiftItem> findAllByResponseIdAndActiveTrueOrderByAddedAt(UUID responseId);

    Optional<GiftItem> findByResponseIdAndCode(UUID responseId, String code);

    /** Активные коды всех ответов мероприятия (для CSV). */
    @Query("""
            select gi from GiftItem gi, Response r
            where gi.responseId = r.id and r.eventId = :eventId and gi.active = true
            order by gi.addedAt
            """)
    List<GiftItem> findActiveByEventId(@Param("eventId") UUID eventId);
}
