package ru.big.survey.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.domain.Event;
import ru.big.survey.domain.GiftAward;
import ru.big.survey.domain.GiftItem;
import ru.big.survey.domain.ManualGiftAward;
import ru.big.survey.domain.Response;
import ru.big.survey.persistence.EventRepository;
import ru.big.survey.persistence.GiftAwardRepository;
import ru.big.survey.persistence.GiftItemRepository;
import ru.big.survey.persistence.ManualGiftAwardRepository;
import ru.big.survey.persistence.ResponseRepository;
import ru.big.survey.security.Actor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Выдача подарков персоналом стенда: карточка посетителя по QR-токену (штатная камера телефона → deep-link)
 * или по короткому коду, отметка выдачи/снятия. Кодов маркировки на один подарок может быть несколько.
 * Телефон и e-mail персоналу не отдаются.
 */
@Service
public class GiftService {

    private final ResponseRepository responses;
    private final EventRepository events;
    private final GiftAwardRepository awards;
    private final GiftItemRepository items;
    private final ManualGiftAwardRepository manualAwards;
    private final TokenService tokens;
    private final AuditService audit;
    private final UserService users;
    private final MarkingCheckService markingCheck;
    private final Json json;
    private final Clock clock;

    public GiftService(ResponseRepository responses, EventRepository events, GiftAwardRepository awards,
                       GiftItemRepository items, TokenService tokens, AuditService audit, UserService users,
                       MarkingCheckService markingCheck, Json json, Clock clock, ManualGiftAwardRepository manualAwards) {
        this.manualAwards = manualAwards;
        this.responses = responses;
        this.events = events;
        this.awards = awards;
        this.items = items;
        this.tokens = tokens;
        this.audit = audit;
        this.users = users;
        this.markingCheck = markingCheck;
        this.json = json;
        this.clock = clock;
    }

    /** Найти ответ по QR-токену либо по паре (мероприятие, код). */
    @Transactional(readOnly = true)
    public ObjectNode lookup(String token, UUID eventId, String code, Actor actor) {
        Response r = resolve(token, eventId, code);
        assertAssigned(actor, r.getEventId());
        return card(r);
    }

    /**
     * Отметить/снять подарок. itemCodes — отсканированные коды подарка (маркировка), может быть несколько или ни одного.
     * result: ok | already | removed | not_changed.
     */
    @Transactional
    public ObjectNode award(String token, UUID eventId, String code, boolean awarded, List<String> itemCodes, Actor actor) {
        Response r = resolve(token, eventId, code);
        assertAssigned(actor, r.getEventId());
        if (!awarded && !actor.isAdmin()) {
            throw ApiException.forbidden("Снимать отметку выдачи подарка может только администратор.");
        }
        Event event = events.findById(r.getEventId()).orElseThrow();
        if (!event.isGiftEnabled()) {
            throw ApiException.conflict("gift_disabled", "На этом мероприятии подарки не выдаются.");
        }
        Set<String> normalized = normalizeItemCodes(itemCodes);
        if (awarded && !r.isGiftAwarded() && event.isGiftMarkRequired() && normalized.isEmpty()) {
            throw ApiException.badRequest("mark_required", "На этом мероприятии выдача без сканирования кода маркировки запрещена.");
        }
        List<String> markWarnings = awarded ? checkCodesInCirculation(normalized, actor) : List.of();
        Instant now = clock.instant();
        String result;
        if (r.isGiftAwarded() == awarded) {
            result = awarded ? "already" : "not_changed";
        } else {
            if (awarded) {
                for (String c : normalized) {
                    GiftItem existing = items.findByResponseIdAndCode(r.getId(), c).orElse(null);
                    if (existing == null) {
                        items.save(GiftItem.of(r.getId(), c, actor.username(), now));
                    } else if (!existing.isActive()) {
                        existing.setActive(true, actor.username(), now);
                        items.save(existing);
                    }
                }
            } else {
                // отмена ошибочной выдачи сотрудником: гасим коды, отсканированные в рамках этой выдачи
                Instant since = r.getGiftAwardedAt();
                if (since != null) {
                    for (GiftItem item : items.findAllByResponseIdAndActiveTrueOrderByAddedAt(r.getId())) {
                        if (!item.getAddedAt().isBefore(since)) {
                            item.setActive(false, actor.username(), now);
                            items.save(item);
                        }
                    }
                }
            }
            r.setGift(awarded, actor.username(), now, responses.nextChangeSeq());
            r.setItemCodeSummary(joinCodes(activeCodes(r.getId())));
            responses.save(r);
            String source = token != null ? "scan" : "code";
            awards.save(GiftAward.of(r.getId(), awarded, actor.username(), source, joinCodes(new ArrayList<>(normalized)), now));
            audit.ok(r.getEventId(), "GIFT", actor.username(), Map.of("responseId", r.getId().toString(), "awarded", awarded,
                    "source", source, "itemCodes", String.join(", ", normalized)));
            result = awarded ? "ok" : "removed";
        }
        ObjectNode view = card(r);
        view.put("result", result);
        if (!markWarnings.isEmpty()) {
            ArrayNode warnings = view.putArray("markWarnings");
            markWarnings.forEach(warnings::add);
        }
        return view;
    }

    /** Выдать один подарок без анкеты. Повтор одного requestId возвращает ранее записанную выдачу. */
    @Transactional
    public ObjectNode awardManual(UUID requestId, UUID eventId, List<String> itemCodes, Actor actor) {
        if (requestId == null || eventId == null) {
            throw ApiException.badRequest("manual_gift", "Укажите мероприятие и идентификатор выдачи.");
        }
        assertAssigned(actor, eventId);
        Set<String> normalized = normalizeItemCodes(itemCodes);
        ManualGiftAward existing = manualAwards.findById(requestId).orElse(null);
        if (existing != null) {
            assertSameManualRequest(existing, eventId, normalized, actor);
            return manualCard(existing, "already");
        }
        Event event = events.findById(eventId)
                .orElseThrow(() -> ApiException.notFound("Мероприятие не найдено."));
        if (!event.isActive()) {
            throw ApiException.gone("Мероприятие отключено. Выдача без анкеты недоступна.");
        }
        if (!event.isGiftEnabled()) {
            throw ApiException.conflict("gift_disabled", "На этом мероприятии подарки не выдаются.");
        }
        if (event.isGiftMarkRequired() && normalized.isEmpty()) {
            throw ApiException.badRequest("mark_required", "На этом мероприятии выдача без сканирования кода маркировки запрещена.");
        }
        List<String> warnings = checkCodesInCirculation(normalized, actor);
        Instant now = clock.instant();
        int inserted = manualAwards.insertIfAbsent(requestId, eventId, now, actor.username(), json.write(normalized));
        ManualGiftAward saved = manualAwards.findById(requestId).orElseThrow();
        assertSameManualRequest(saved, eventId, normalized, actor);
        if (inserted == 1) {
            audit.okInTransaction(eventId, "GIFT", actor.username(), Map.of("awardId", requestId.toString(),
                    "awarded", true, "source", "manual", "itemCodes", String.join(", ", normalized)));
        }
        ObjectNode view = manualCard(saved, inserted == 1 ? "ok" : "already");
        if (!warnings.isEmpty()) {
            ArrayNode out = view.putArray("markWarnings");
            warnings.forEach(out::add);
        }
        return view;
    }

    private void assertSameManualRequest(ManualGiftAward saved, UUID eventId, Set<String> codes, Actor actor) {
        Set<String> savedCodes = new LinkedHashSet<>();
        json.read(saved.getItemCodes()).forEach(c -> savedCodes.add(c.asString()));
        if (!saved.getEventId().equals(eventId) || !saved.getByUser().equals(actor.username()) || !savedCodes.equals(codes)) {
            throw ApiException.conflict("request_reused", "Идентификатор выдачи уже использован с другими данными.");
        }
    }

    private ObjectNode manualCard(ManualGiftAward award, String result) {
        ObjectNode view = json.object();
        view.put("awardId", award.getId().toString());
        view.put("eventId", award.getEventId().toString());
        view.put("awardedAt", award.getAt().toString());
        view.put("awardedBy", award.getByUser());
        view.set("itemCodes", json.read(award.getItemCodes()));
        view.put("result", result);
        return view;
    }

    /**
     * Спорное снятие отметки выдачи администратором («мне ничего не выдавали»): галка снимается,
     * дата/автор выдачи и коды маркировки сохраняются, повторная выдача возможна (с новым КМ).
     */
    @Transactional
    public ObjectNode disputeUnaward(UUID responseId, Actor actor) {
        Response r = responses.findById(responseId)
                .orElseThrow(() -> ApiException.notFound("Анкета не найдена."));
        if (!r.isGiftAwarded()) {
            throw ApiException.conflict("not_awarded", "Подарок по этой анкете не отмечен как выданный.");
        }
        Instant now = clock.instant();
        r.disputeGift(responses.nextChangeSeq());
        responses.save(r);
        awards.save(GiftAward.of(r.getId(), false, actor.username(), "admin", null, now));
        audit.ok(r.getEventId(), "GIFT", actor.username(),
                Map.of("responseId", r.getId().toString(), "awarded", false, "source", "admin", "dispute", true));
        return card(r);
    }

    /**
     * Проверка статуса кода на стенде сразу после сканирования (мгновенная обратная связь).
     * status: in_circulation | rejected | unknown | disabled; blocking — режим сотрудника
     * (true: «не в обороте» блокирует выдачу, false: только предупреждение).
     */
    public ObjectNode checkItemCode(String rawCode, Actor actor) {
        ObjectNode out = json.object();
        out.put("blocking", blocksRejected(actor));
        String code = normalizeItemCode(rawCode);
        if (code == null) {
            out.put("status", "rejected");
            out.put("message", "Пустой код маркировки.");
            return out;
        }
        if (!markingCheck.enabled()) {
            out.put("status", "disabled");
            return out;
        }
        MarkingCheckClient.Verdict verdict = markingCheck.check(code);
        switch (verdict.status()) {
            case IN_CIRCULATION -> out.put("status", "in_circulation");
            case REJECTED -> {
                out.put("status", "rejected");
                out.put("message", verdict.detail());
                audit.ok(null, "MARKING_CHECK", actor.username(),
                        Map.of("code", code, "status", "rejected", "message", verdict.detail()));
            }
            case UNKNOWN -> {
                out.put("status", "unknown");
                out.put("message", markingCheck.failOpen()
                        ? verdict.detail() + " Код принят без проверки."
                        : verdict.detail() + " Выдача с этим кодом будет заблокирована.");
                audit.error(null, "MARKING_CHECK", actor.username(),
                        Map.of("code", code, "status", "unknown", "message", verdict.detail()));
            }
        }
        return out;
    }

    /** Режим сотрудника: блокировать ли выдачу при коде «не в обороте» (галка в карточке пользователя). */
    private boolean blocksRejected(Actor actor) {
        try {
            return users.require(actor.username()).isBlockRejectedMarks();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Серверная страховка при выдаче (клиенту не доверяем): коды «не в обороте» блокируются,
     * если у сотрудника включена галка блокировки; иначе выдача проходит, а предупреждения
     * возвращаются в ответе (markWarnings) и фиксируются в аудите.
     */
    private List<String> checkCodesInCirculation(Set<String> codes, Actor actor) {
        if (codes.isEmpty() || !markingCheck.enabled()) {
            return List.of();
        }
        boolean blocking = blocksRejected(actor);
        List<String> warnings = new ArrayList<>();
        for (String code : codes) {
            MarkingCheckClient.Verdict verdict = markingCheck.check(code);
            if (verdict.status() == MarkingCheckClient.Status.REJECTED) {
                if (blocking) {
                    audit.error(null, "MARKING_CHECK", actor.username(),
                            Map.of("code", code, "status", "rejected_blocked", "message", verdict.detail()));
                    throw ApiException.badRequest("mark_not_in_circulation",
                            "Код " + code + " нельзя выдавать: " + verdict.detail());
                }
                audit.ok(null, "MARKING_CHECK", actor.username(),
                        Map.of("code", code, "status", "rejected_warned", "message", verdict.detail()));
                warnings.add("Код " + code + ": " + verdict.detail() + " Выдано без блокировки.");
            } else if (verdict.status() == MarkingCheckClient.Status.UNKNOWN) {
                audit.error(null, "MARKING_CHECK", actor.username(),
                        Map.of("code", code, "status", "unknown", "message", verdict.detail()));
                if (!markingCheck.failOpen()) {
                    throw ApiException.upstream("Проверка кода в «Честном знаке» недоступна, выдача заблокирована. " + verdict.detail());
                }
                warnings.add("Код " + code + " не проверен: " + verdict.detail());
            }
        }
        return warnings;
    }

    /** Коды маркировки: убираем управляющие символы GS1 (FNC1/GS) и пробелы, отбрасываем пустые и дубли. */
    static Set<String> normalizeItemCodes(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String r : raw) {
            String s = normalizeItemCode(r);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** Код маркировки: убираем управляющие символы GS1 (FNC1/GS) и пробелы по краям, ограничиваем длину. */
    static String normalizeItemCode(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replace("\u001d", "").replace("\u00e8", "").trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 512 ? s.substring(0, 512) : s;
    }

    private List<String> activeCodes(UUID responseId) {
        List<String> out = new ArrayList<>();
        for (GiftItem item : items.findAllByResponseIdAndActiveTrueOrderByAddedAt(responseId)) {
            out.add(item.getCode());
        }
        return out;
    }

    private static String joinCodes(List<String> codes) {
        return codes.isEmpty() ? null : String.join(", ", codes);
    }

    /** STAFF и SUPERVISOR обслуживают только назначенные мероприятия; ADMIN — любые. */
    private void assertAssigned(Actor actor, UUID eventId) {
        Set<UUID> allowed = users.allowedEventIds(actor);
        if (allowed != null && !allowed.contains(eventId)) {
            throw ApiException.forbidden("Это мероприятие вам не назначено. Обратитесь к администратору.");
        }
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

    /** Карточка для персонала: имя, город, время, статус подарка, активные коды маркировки. Без телефона и e-mail. */
    public ObjectNode card(Response r) {
        Event event = events.findById(r.getEventId()).orElse(null);
        JsonNode answers = json.read(r.getAnswers());
        ObjectNode c = json.object();
        c.put("responseId", r.getId().toString());
        c.put("eventId", r.getEventId().toString());
        c.put("eventName", event == null ? "" : event.getName());
        c.put("giftEnabled", event != null && event.isGiftEnabled());
        c.put("giftMarked", event != null && event.isGiftMarked());
        c.put("giftMarkRequired", event != null && event.isGiftMarkRequired());
        c.put("visitor", visitorName(answers));
        c.put("city", text(answers, "Город"));
        c.put("submittedAt", r.getSubmittedAt().toString());
        c.put("giftCode", r.getGiftCode());
        c.put("awarded", r.isGiftAwarded());
        if (r.getGiftAwardedAt() != null) {
            c.put("awardedAt", r.getGiftAwardedAt().toString());
            c.put("awardedBy", r.getGiftAwardedBy());
        }
        ArrayNode codes = c.putArray("itemCodes");
        for (String code : activeCodes(r.getId())) {
            codes.add(code);
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
