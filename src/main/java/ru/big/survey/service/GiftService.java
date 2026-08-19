package ru.big.survey.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.domain.Event;
import ru.big.survey.domain.GiftAward;
import ru.big.survey.domain.Response;
import ru.big.survey.persistence.EventRepository;
import ru.big.survey.persistence.GiftAwardRepository;
import ru.big.survey.persistence.ResponseRepository;
import ru.big.survey.security.Actor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Выдача подарков персоналом стенда: карточка посетителя по QR-токену (штатная камера телефона → deep-link)
 * или по короткому коду, отметка выдачи/снятия. Телефон и e-mail персоналу не отдаются.
 */
@Service
public class GiftService {

    private final ResponseRepository responses;
    private final EventRepository events;
    private final GiftAwardRepository awards;
    private final TokenService tokens;
    private final AuditService audit;
    private final Json json;
    private final Clock clock;

    public GiftService(ResponseRepository responses, EventRepository events, GiftAwardRepository awards,
                       TokenService tokens, AuditService audit, Json json, Clock clock) {
        this.responses = responses;
        this.events = events;
        this.awards = awards;
        this.tokens = tokens;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** Найти ответ по QR-токену либо по паре (мероприятие, код). */
    @Transactional(readOnly = true)
    public ObjectNode lookup(String token, UUID eventId, String code) {
        return card(resolve(token, eventId, code));
    }

    /** Отметить/снять подарок. result: ok | already | removed | not_changed. */
    @Transactional
    public ObjectNode award(String token, UUID eventId, String code, boolean awarded, Actor actor) {
        Response r = resolve(token, eventId, code);
        Event event = events.findById(r.getEventId()).orElseThrow();
        if (!event.isGiftEnabled()) {
            throw ApiException.conflict("gift_disabled", "На этом мероприятии подарки не выдаются.");
        }
        Instant now = clock.instant();
        String result;
        if (r.isGiftAwarded() == awarded) {
            result = awarded ? "already" : "not_changed";
        } else {
            r.setGift(awarded, actor.username(), now, responses.nextChangeSeq());
            responses.save(r);
            awards.save(GiftAward.of(r.getId(), awarded, actor.username(), token != null ? "scan" : "code", now));
            audit.ok(r.getEventId(), "GIFT", actor.username(), Map.of("responseId", r.getId().toString(), "awarded", awarded,
                    "source", token != null ? "scan" : "code"));
            result = awarded ? "ok" : "removed";
        }
        ObjectNode view = card(r);
        view.put("result", result);
        return view;
    }

    private Response resolve(String token, UUID eventId, String code) {
        if (token != null && !token.isBlank()) {
            TokenService.Gift gift = tokens.parseGift(token)
                    .orElseThrow(() -> ApiException.badRequest("bad_token", "QR-код не распознан или устарел. Введите код с экрана посетителя."));
            return responses.findById(gift.responseId())
                    .orElseThrow(() -> ApiException.notFound("Анкета по этому QR-коду не найдена."));
        }
        if (eventId == null || code == null || code.isBlank()) {
            throw ApiException.badRequest("code", "Укажите мероприятие и код с экрана посетителя.");
        }
        String normalized = code.replaceAll("\\D", "");
        return responses.findByEventIdAndGiftCode(eventId, normalized)
                .orElseThrow(() -> ApiException.notFound("Анкета с таким кодом на этом мероприятии не найдена."));
    }

    /** Карточка для персонала: имя, город, время, статус подарка. Без телефона и e-mail. */
    public ObjectNode card(Response r) {
        Event event = events.findById(r.getEventId()).orElse(null);
        JsonNode answers = json.read(r.getAnswers());
        ObjectNode c = json.object();
        c.put("responseId", r.getId().toString());
        c.put("eventId", r.getEventId().toString());
        c.put("eventName", event == null ? "" : event.getName());
        c.put("giftEnabled", event != null && event.isGiftEnabled());
        c.put("visitor", visitorName(answers));
        c.put("city", text(answers, "Город"));
        c.put("submittedAt", r.getSubmittedAt().toString());
        c.put("giftCode", r.getGiftCode());
        c.put("awarded", r.isGiftAwarded());
        if (r.getGiftAwardedAt() != null) {
            c.put("awardedAt", r.getGiftAwardedAt().toString());
            c.put("awardedBy", r.getGiftAwardedBy());
        }
        return c;
    }

    /** «Фамилия Имя» из ответов; если таких полей нет — первое непустое строковое значение, кроме телефона/e-mail. */
    public static String visitorName(JsonNode answers) {
        String surname = text(answers, "Фамилия");
        String name = text(answers, "Имя");
        String full = (surname + " " + name).trim();
        if (!full.isEmpty()) {
            return full;
        }
        for (String key : new String[] {"ФИО", "Name", "name", "Имя и фамилия"}) {
            String v = text(answers, key);
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "Посетитель";
    }

    static String text(JsonNode answers, String key) {
        if (answers == null) {
            return "";
        }
        JsonNode v = answers.get(key);
        return v == null || v.isNull() ? "" : (v.isString() ? v.stringValue().trim() : v.asString());
    }
}
