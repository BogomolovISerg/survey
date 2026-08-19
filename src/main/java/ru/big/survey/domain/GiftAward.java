package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** История выдачи/снятия подарка по ответу. */
@Entity
@Table(name = "gift_award")
public class GiftAward {

    @Id
    private UUID id;

    @Column(name = "response_id", nullable = false)
    private UUID responseId;

    @Column(nullable = false)
    private boolean awarded;

    @Column(nullable = false)
    private Instant at;

    @Column(name = "by_user", nullable = false)
    private String byUser;

    /** scan | code | admin */
    @Column(nullable = false, length = 16)
    private String source;

    protected GiftAward() {
    }

    public static GiftAward of(UUID responseId, boolean awarded, String byUser, String source, Instant now) {
        GiftAward g = new GiftAward();
        g.id = UUID.randomUUID();
        g.responseId = responseId;
        g.awarded = awarded;
        g.byUser = byUser;
        g.source = source;
        g.at = now;
        return g;
    }

    public UUID getId() { return id; }
    public UUID getResponseId() { return responseId; }
    public boolean isAwarded() { return awarded; }
    public Instant getAt() { return at; }
    public String getByUser() { return byUser; }
    public String getSource() { return source; }
}
