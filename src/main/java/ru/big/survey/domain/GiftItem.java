package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Код маркировки, отсканированный при выдаче подарка. На один подарок кодов может быть несколько.
 * active=false — код относился к ошибочной выдаче, отменённой сотрудником сразу (в 1С не выгружается);
 * при спорном снятии отметки администратором коды остаются активными — подарок фактически выдавался.
 */
@Entity
@Table(name = "gift_item")
public class GiftItem {

    @Id
    private UUID id;

    @Column(name = "response_id", nullable = false)
    private UUID responseId;

    @Column(nullable = false, length = 512)
    private String code;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "added_by", nullable = false, length = 128)
    private String addedBy;

    protected GiftItem() {
    }

    public static GiftItem of(UUID responseId, String code, String addedBy, Instant now) {
        GiftItem g = new GiftItem();
        g.id = UUID.randomUUID();
        g.responseId = responseId;
        g.code = code;
        g.active = true;
        g.addedAt = now;
        g.addedBy = addedBy;
        return g;
    }

    /** Повторный скан того же кода или отмена выдачи. */
    public void setActive(boolean active, String byUser, Instant now) {
        this.active = active;
        if (active) {
            this.addedAt = now;
            this.addedBy = byUser;
        }
    }

    public UUID getId() { return id; }
    public UUID getResponseId() { return responseId; }
    public String getCode() { return code; }
    public boolean isActive() { return active; }
    public Instant getAddedAt() { return addedAt; }
    public String getAddedBy() { return addedBy; }
}
