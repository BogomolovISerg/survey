package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Курсор подтверждённой выгрузки ответов в 1С по мероприятию. */
@Entity
@Table(name = "sync_state")
public class SyncState {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "acked_seq", nullable = false)
    private long ackedSeq;

    @Column(name = "last_export_at")
    private Instant lastExportAt;

    @Column(name = "last_ack_at")
    private Instant lastAckAt;

    @Column(name = "last_publish_at")
    private Instant lastPublishAt;

    protected SyncState() {
    }

    public static SyncState create(UUID eventId) {
        SyncState s = new SyncState();
        s.eventId = eventId;
        return s;
    }

    public void published(Instant now) { this.lastPublishAt = now; }
    public void exported(Instant now) { this.lastExportAt = now; }

    public void ack(long seq, Instant now) {
        if (seq > ackedSeq) {
            ackedSeq = seq;
        }
        lastAckAt = now;
    }

    public UUID getEventId() { return eventId; }
    public long getAckedSeq() { return ackedSeq; }
    public Instant getLastExportAt() { return lastExportAt; }
    public Instant getLastAckAt() { return lastAckAt; }
    public Instant getLastPublishAt() { return lastPublishAt; }
}
