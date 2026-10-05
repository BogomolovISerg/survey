package ru.big.survey.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.big.survey.security.Actor;
import ru.big.survey.service.AdminService;
import ru.big.survey.service.ApiException;
import ru.big.survey.service.GiftService;
import ru.big.survey.service.UserService;
import tools.jackson.databind.node.ObjectNode;

/**
 * Панель стенда (роли STAFF, SUPERVISOR, ADMIN).
 *   GET  /api/v1/staff/events                 — текущие мероприятия
 *   GET  /api/v1/staff/events/{guid}/stats    — живые счётчики (limit — сколько последних анкет, 1–50)
 *   POST /api/v1/staff/gift/lookup {token} | {eventId, code}          — карточка посетителя
 *   POST /api/v1/staff/gift/award  {token | eventId+code, awarded, itemCodes[]} — выдать/снять подарок
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffController {

    private final AdminService admin;
    private final GiftService gifts;
    private final UserService users;

    public StaffController(AdminService admin, GiftService gifts, UserService users) {
        this.admin = admin;
        this.gifts = gifts;
        this.users = users;
    }

    /** Текущие мероприятия: STAFF и SUPERVISOR видят только назначенные, ADMIN — все. */
    @GetMapping("/events")
    public List<ObjectNode> events(Authentication authentication) {
        return admin.currentEvents(users.allowedEventIds(Actor.of(authentication)));
    }

    @GetMapping("/events/{eventId}/stats")
    public ObjectNode stats(@PathVariable UUID eventId,
                            @RequestParam(defaultValue = "10") int limit,
                            Authentication authentication) {
        Set<UUID> allowed = users.allowedEventIds(Actor.of(authentication));
        if (allowed != null && !allowed.contains(eventId)) {
            throw ApiException.forbidden("Это мероприятие вам не назначено. Обратитесь к администратору.");
        }
        return admin.stats(eventId, limit);
    }

    public record GiftLookupRequest(String token, UUID eventId, String code) {}

    @PostMapping("/gift/lookup")
    public ObjectNode lookup(@RequestBody GiftLookupRequest request, Authentication authentication) {
        return gifts.lookup(request.token(), request.eventId(), request.code(), Actor.of(authentication));
    }

    /** Проверка кода маркировки в «Честном знаке» сразу после сканирования: {status: in_circulation|rejected|unknown|disabled}. */
    public record ItemCodeCheckRequest(String code) {}

    @PostMapping("/gift/item-code/check")
    public ObjectNode checkItemCode(@RequestBody ItemCodeCheckRequest request, Authentication authentication) {
        return gifts.checkItemCode(request.code(), Actor.of(authentication));
    }

    /** itemCodes — коды подарка (QR/DataMatrix маркировки), отсканированные при выдаче; itemCode — совместимость с одиночным сканом. */
    public record GiftAwardRequest(String token, UUID eventId, String code, Boolean awarded, String itemCode, List<String> itemCodes) {}

    @PostMapping("/gift/award")
    public ObjectNode award(@RequestBody GiftAwardRequest request, Authentication authentication) {
        boolean awarded = request.awarded() == null || request.awarded();
        List<String> codes = new java.util.ArrayList<>();
        if (request.itemCodes() != null) {
            codes.addAll(request.itemCodes());
        }
        if (request.itemCode() != null) {
            codes.add(request.itemCode());
        }
        return gifts.award(request.token(), request.eventId(), request.code(), awarded, codes, Actor.of(authentication));
    }
    public record ManualGiftAwardRequest(UUID requestId, UUID eventId, List<String> itemCodes) {}

    /** Выдача посетителю без QR-кода и заполнения анкеты. */
    @PostMapping("/gift/manual")
    public ObjectNode awardManual(@RequestBody ManualGiftAwardRequest request, Authentication authentication) {
        return gifts.awardManual(request.requestId(), request.eventId(), request.itemCodes(), Actor.of(authentication));
    }

}
