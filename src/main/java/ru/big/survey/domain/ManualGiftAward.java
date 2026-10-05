package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Выдача одного подарка без анкеты. id — ключ повторного запроса с клиента. */
@Entity
@Table(name = "manual_gift_award")
public class ManualGiftAward {
    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private Instant at;

    @Column(name = "by_user", nullable = false, length = 128)
    private String byUser;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "item_codes", nullable = false)
    private String itemCodes;

    /** Общая последовательность обмена с 1С; назначается БД при вставке. */
    @Column(name = "change_seq", nullable = false, insertable = false, updatable = false)
    private long changeSeq;

    protected ManualGiftAward() {}

    public static ManualGiftAward of(UUID id, UUID eventId, String byUser, Instant at, String itemCodes) {
        ManualGiftAward award = new ManualGiftAward();
        award.id = id;
        award.eventId = eventId;
        award.byUser = byUser;
        award.at = at;
        award.itemCodes = itemCodes;
        return award;
    }

    public long getChangeSeq() { return changeSeq; }
    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public Instant getAt() { return at; }
    public String getByUser() { return byUser; }
    public String getItemCodes() { return itemCodes; }
}
