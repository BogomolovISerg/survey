package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Мероприятие (выставка). id = GUID элемента справочника бигМероприятия в 1С. */
@Entity
@Table(name = "event")
public class Event {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "starts_on")
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(name = "gift_enabled", nullable = false)
    private boolean giftEnabled;

    @Column(nullable = false)
    private boolean active = true;

    /** JSON: { "mode": "light|dark", "accent": "#RRGGBB", "invertLogoOnDark": true } */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "theme")
    private String theme;

    @Column(name = "current_version", nullable = false)
    private int currentVersion;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Event() {
    }

    public static Event create(UUID id, Instant now) {
        Event e = new Event();
        e.id = id;
        e.name = "";
        e.publishedAt = now;
        e.updatedAt = now;
        return e;
    }

    public void apply(String name, LocalDate startsOn, LocalDate endsOn, boolean giftEnabled, boolean active, String theme, Instant now) {
        this.name = name == null ? "" : name.trim();
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.giftEnabled = giftEnabled;
        this.active = active;
        this.theme = theme;
        this.updatedAt = now;
    }

    public void publishedVersion(int version, Instant now) {
        this.currentVersion = version;
        this.publishedAt = now;
        this.updatedAt = now;
    }

    public void setActive(boolean active, Instant now) {
        this.active = active;
        this.updatedAt = now;
    }

    /** Мероприятие принимает анкеты: активно и опубликована хотя бы одна версия. */
    public boolean acceptsResponses() {
        return active && currentVersion > 0;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public LocalDate getStartsOn() { return startsOn; }
    public LocalDate getEndsOn() { return endsOn; }
    public boolean isGiftEnabled() { return giftEnabled; }
    public boolean isActive() { return active; }
    public String getTheme() { return theme; }
    public int getCurrentVersion() { return currentVersion; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
