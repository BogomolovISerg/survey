package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Ответ посетителя на анкету мероприятия. Один ответ на телефон в рамках мероприятия. */
@Entity
@Table(name = "response")
public class Response {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "questionnaire_version", nullable = false)
    private int questionnaireVersion;

    /** Телефон цифрами: 7XXXXXXXXXX. */
    @Column(nullable = false, length = 20)
    private String phone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String answers;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "consent_at")
    private Instant consentAt;

    @Column(name = "gift_awarded", nullable = false)
    private boolean giftAwarded;

    @Column(name = "gift_awarded_at")
    private Instant giftAwardedAt;

    @Column(name = "gift_awarded_by")
    private String giftAwardedBy;

    @Column(name = "gift_code", nullable = false, length = 8)
    private String giftCode;

    @Column(name = "change_seq", nullable = false)
    private long changeSeq;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "client")
    private String client;

    protected Response() {
    }

    public static Response create(UUID eventId, int questionnaireVersion, String phone, String answers,
                                  Instant consentAt, String giftCode, String client, long changeSeq, Instant now) {
        Response r = new Response();
        r.id = UUID.randomUUID();
        r.eventId = eventId;
        r.questionnaireVersion = questionnaireVersion;
        r.phone = phone;
        r.answers = answers;
        r.submittedAt = now;
        r.consentAt = consentAt;
        r.giftCode = giftCode;
        r.client = client;
        r.changeSeq = changeSeq;
        return r;
    }

    /** Отметить/снять выдачу подарка. Возвращает false, если состояние не изменилось. */
    public boolean setGift(boolean awarded, String byUser, Instant now, long changeSeq) {
        if (this.giftAwarded == awarded) {
            return false;
        }
        this.giftAwarded = awarded;
        this.giftAwardedAt = awarded ? now : null;
        this.giftAwardedBy = awarded ? byUser : null;
        this.changeSeq = changeSeq;
        return true;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public int getQuestionnaireVersion() { return questionnaireVersion; }
    public String getPhone() { return phone; }
    public String getAnswers() { return answers; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getConsentAt() { return consentAt; }
    public boolean isGiftAwarded() { return giftAwarded; }
    public Instant getGiftAwardedAt() { return giftAwardedAt; }
    public String getGiftAwardedBy() { return giftAwardedBy; }
    public String getGiftCode() { return giftCode; }
    public long getChangeSeq() { return changeSeq; }
    public String getClient() { return client; }
}
