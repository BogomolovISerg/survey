package ru.big.survey.api;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.big.survey.security.Actor;
import ru.big.survey.service.AdminService;
import ru.big.survey.service.GiftService;
import tools.jackson.databind.node.ObjectNode;

/**
 * Панель стенда (роли STAFF, ADMIN).
 *   GET  /api/v1/staff/events                 — текущие мероприятия
 *   GET  /api/v1/staff/events/{guid}/stats    — живые счётчики
 *   POST /api/v1/staff/gift/lookup {token} | {eventId, code}          — карточка посетителя
 *   POST /api/v1/staff/gift/award  {token | eventId+code, awarded}    — выдать/снять подарок
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffController {

    private final AdminService admin;
    private final GiftService gifts;

    public StaffController(AdminService admin, GiftService gifts) {
        this.admin = admin;
        this.gifts = gifts;
    }

    @GetMapping("/events")
    public List<ObjectNode> events() {
        return admin.currentEvents();
    }

    @GetMapping("/events/{eventId}/stats")
    public ObjectNode stats(@PathVariable UUID eventId) {
        return admin.stats(eventId);
    }

    public record GiftLookupRequest(String token, UUID eventId, String code) {}

    @PostMapping("/gift/lookup")
    public ObjectNode lookup(@RequestBody GiftLookupRequest request) {
        return gifts.lookup(request.token(), request.eventId(), request.code());
    }

    public record GiftAwardRequest(String token, UUID eventId, String code, Boolean awarded) {}

    @PostMapping("/gift/award")
    public ObjectNode award(@RequestBody GiftAwardRequest request, Authentication authentication) {
        boolean awarded = request.awarded() == null || request.awarded();
        return gifts.award(request.token(), request.eventId(), request.code(), awarded, Actor.of(authentication));
    }
}
