package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Журнал обмена и административных действий. */
@Entity
@Table(name = "sync_log")
public class SyncLog {

    @Id
    private UUID id;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String details;

    protected SyncLog() {
    }

    public static SyncLog of(UUID eventId, String kind, String actor, String status, String detailsJson, Instant now) {
        SyncLog l = new SyncLog();
        l.id = UUID.randomUUID();
        l.eventId = eventId;
        l.kind = kind;
        l.actor = actor;
        l.status = status;
        l.details = detailsJson;
        l.at = now;
        return l;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getKind() { return kind; }
    public Instant getAt() { return at; }
    public String getActor() { return actor; }
    public String getStatus() { return status; }
    public String getDetails() { return details; }
}
