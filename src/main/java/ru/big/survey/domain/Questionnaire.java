package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Версия схемы анкеты мероприятия (JSON формата v1: enums / components / schema / style / output). */
@Entity
@Table(name = "questionnaire")
public class Questionnaire {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema", nullable = false)
    private String schema;

    @Column(nullable = false, length = 64)
    private String checksum;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    protected Questionnaire() {
    }

    public static Questionnaire create(UUID eventId, int version, String schema, String checksum, Instant now) {
        Questionnaire q = new Questionnaire();
        q.id = UUID.randomUUID();
        q.eventId = eventId;
        q.version = version;
        q.schema = schema;
        q.checksum = checksum;
        q.publishedAt = now;
        return q;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public int getVersion() { return version; }
    public String getSchema() { return schema; }
    public String getChecksum() { return checksum; }
    public Instant getPublishedAt() { return publishedAt; }
}
