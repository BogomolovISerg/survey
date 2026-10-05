package ru.big.survey.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.config.SurveyProperties;
import ru.big.survey.domain.Event;
import ru.big.survey.domain.GiftItem;
import ru.big.survey.domain.ManualGiftAward;
import ru.big.survey.domain.Questionnaire;
import ru.big.survey.domain.Response;
import ru.big.survey.domain.SyncState;
import ru.big.survey.persistence.EventRepository;
import ru.big.survey.persistence.GiftItemRepository;
import ru.big.survey.persistence.ManualGiftAwardRepository;
import ru.big.survey.persistence.QuestionnaireRepository;
import ru.big.survey.persistence.ResponseRepository;
import ru.big.survey.persistence.SyncStateRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Обмен с 1С:ERP (1С — инициатор):
 *  publish  — создать/обновить мероприятие и опубликовать версию анкеты (идемпотентно по checksum);
 *  export   — ответы и выдачи без анкеты с change_seq > after;
 *  ack      — подтвердить курсор после успешной записи в РС бигДанныеАнкет;
 *  status   — счётчики для карточки мероприятия в 1С.
 */
@Service
public class SyncService {

    public static final int MAX_PAGE = 1000;

    private final EventRepository events;
    private final QuestionnaireRepository questionnaires;
    private final ResponseRepository responses;
    private final SyncStateRepository states;
    private final GiftItemRepository giftItems;
    private final ManualGiftAwardRepository manualAwards;
    private final SchemaService schemas;
    private final AuditService audit;
    private final Json json;
    private final SurveyProperties properties;
    private final Clock clock;

    public SyncService(EventRepository events, QuestionnaireRepository questionnaires, ResponseRepository responses,
                       SyncStateRepository states, GiftItemRepository giftItems, SchemaService schemas,
                       AuditService audit, Json json, SurveyProperties properties, Clock clock, ManualGiftAwardRepository manualAwards) {
        this.manualAwards = manualAwards;
        this.giftItems = giftItems;
        this.events = events;
        this.questionnaires = questionnaires;
        this.responses = responses;
        this.states = states;
        this.schemas = schemas;
        this.audit = audit;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    public record PublishCommand(String name, LocalDate startsOn, LocalDate endsOn, Boolean giftEnabled,
                                 Boolean giftMarked, Boolean giftMarkRequired,
                                 Boolean active, JsonNode theme, JsonNode questionnaire, String giftAwardMode) {}

    public record PublishResult(UUID eventId, int version, boolean changed, String publicUrl, Instant publishedAt) {}

    @Transactional
    public PublishResult publish(UUID eventId, PublishCommand command, String actor) {
        try {
            if (command.questionnaire() == null) {
                throw ApiException.badRequest("schema", "Не передана анкета (questionnaire).");
            }
            schemas.validate(command.questionnaire());
            Instant now = clock.instant();
            Event event = events.findById(eventId).orElseGet(() -> Event.create(eventId, now));
            String theme = command.theme() == null || command.theme().isNull() ? event.getTheme() : json.write(command.theme());
            event.apply(command.name(), command.startsOn(), command.endsOn(),
                    command.giftEnabled() != null ? command.giftEnabled() : event.isGiftEnabled(),
                    command.giftMarked() != null ? command.giftMarked() : event.isGiftMarked(),
                    command.giftMarkRequired() != null ? command.giftMarkRequired() : event.isGiftMarkRequired(),
                    command.active() != null ? command.active() : true,
                    theme, now);
            if (command.giftAwardMode() != null) {
                if (!java.util.Set.of("questionnaire", "manual", "both").contains(command.giftAwardMode())) {
                    throw ApiException.badRequest("gift_award_mode", "Неизвестный режим кнопок выдачи подарков.");
                }
                event.setGiftAwardMode(command.giftAwardMode());
            }
            // новое мероприятие должно попасть в БД раньше версии анкеты (внешний ключ)
            events.saveAndFlush(event);

            String checksum = schemas.checksum(command.questionnaire());
            Questionnaire latest = questionnaires.findFirstByEventIdOrderByVersionDesc(eventId).orElse(null);
            boolean changed;
            int version;
            if (latest != null && latest.getChecksum().equals(checksum)) {
                changed = false;
                version = latest.getVersion();
            } else {
                version = latest == null ? 1 : latest.getVersion() + 1;
                questionnaires.save(Questionnaire.create(eventId, version, json.write(command.questionnaire()), checksum, now));
                changed = true;
            }
            event.publishedVersion(version, now);
            events.save(event);
            SyncState state = states.findById(eventId).orElseGet(() -> SyncState.create(eventId));
            state.published(now);
            states.save(state);
            audit.ok(eventId, "PUBLISH", actor, Map.of("version", version, "changed", changed, "name", event.getName()));
            return new PublishResult(eventId, version, changed, publicUrl(eventId), now);
        } catch (ApiException e) {
            audit.error(eventId, "PUBLISH", actor, Map.of("error", e.getMessage()));
            throw e;
        }
    }

    public String publicUrl(UUID eventId) {
        return properties.getPublicBaseUrl() + "/e/" + eventId;
    }

    public record ExportPage(List<ObjectNode> items, long nextAfter, boolean hasMore, long ackedSeq) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ExportPage export(UUID eventId, long after, int limit, String actor) {
        requireEvent(eventId);
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        // В каждой таблице выбираем не больше size + 1, затем объединяем по общему seq.
        // Обе таблицы читаются в одном снимке БД, чтобы новые записи между запросами не сдвинули курсор.
        List<ObjectNode> combined = new ArrayList<>(2 * (size + 1));
        for (Response response : responses.findChangedAfter(eventId, after, PageRequest.of(0, size + 1))) {
            combined.add(exportView(response));
        }
        for (ManualGiftAward award : manualAwards.findChangedAfter(eventId, after, PageRequest.of(0, size + 1))) {
            combined.add(exportManualGiftView(award));
        }
        combined.sort(java.util.Comparator.comparingLong(item -> item.path("seq").asLong()));
        boolean hasMore = combined.size() > size;
        List<ObjectNode> items = new ArrayList<>(combined.subList(0, Math.min(size, combined.size())));
        long last = items.isEmpty() ? after : items.get(items.size() - 1).path("seq").asLong();
        SyncState state = states.findById(eventId).orElseGet(() -> SyncState.create(eventId));
        state.exported(clock.instant());
        states.save(state);
        audit.ok(eventId, "EXPORT", actor, Map.of("after", after, "count", items.size(), "nextAfter", last, "hasMore", hasMore));
        return new ExportPage(items, last, hasMore, state.getAckedSeq());
    }

    @Transactional
    public long ack(UUID eventId, long seq, String actor) {
        requireEvent(eventId);
        SyncState state = states.findById(eventId).orElseGet(() -> SyncState.create(eventId));
        state.ack(seq, clock.instant());
        states.save(state);
        audit.ok(eventId, "ACK", actor, Map.of("seq", seq, "ackedSeq", state.getAckedSeq()));
        return state.getAckedSeq();
    }

    @Transactional(readOnly = true)
    public ObjectNode status(UUID eventId) {
        Event event = requireEvent(eventId);
        SyncState state = states.findById(eventId).orElse(null);
        long acked = state == null ? 0 : state.getAckedSeq();
        ObjectNode node = json.object();
        node.put("eventId", eventId.toString());
        node.put("name", event.getName());
        node.put("active", event.isActive());
        node.put("giftEnabled", event.isGiftEnabled());
        node.put("giftAwardMode", event.getGiftAwardMode());
        node.put("giftMarked", event.isGiftMarked());
        node.put("giftMarkRequired", event.isGiftMarkRequired());
        node.put("version", event.getCurrentVersion());
        node.put("publicUrl", publicUrl(eventId));
        node.put("responses", responses.countByEventId(eventId));
        long manualCount = manualAwards.countByEventId(eventId);
        node.put("giftsAwarded", responses.countByEventIdAndGiftAwardedTrue(eventId) + manualCount);
        node.put("manualGiftsAwarded", manualCount);
        node.put("ackedSeq", acked);
        node.put("pending", responses.countByEventIdAndChangeSeqGreaterThan(eventId, acked)
                + manualAwards.countByEventIdAndChangeSeqGreaterThan(eventId, acked));
        node.put("publishedAt", event.getPublishedAt().toString());
        if (state != null && state.getLastExportAt() != null) {
            node.put("lastExportAt", state.getLastExportAt().toString());
        }
        if (state != null && state.getLastAckAt() != null) {
            node.put("lastAckAt", state.getLastAckAt().toString());
        }
        return node;
    }

    private Event requireEvent(UUID eventId) {
        return events.findById(eventId).orElseThrow(() -> ApiException.notFound("Мероприятие не опубликовано в сервисе."));
    }

    /** Представление ответа для 1С. */
    public ObjectNode exportView(Response r) {
        ObjectNode node = json.object();
        node.put("recordType", "response");
        node.put("seq", r.getChangeSeq());
        node.put("id", r.getId().toString());
        node.put("phone", r.getPhone());
        node.put("submittedAt", r.getSubmittedAt().toString());
        node.put("questionnaireVersion", r.getQuestionnaireVersion());
        if (r.getConsentAt() != null) {
            node.put("consentAt", r.getConsentAt().toString());
        }
        node.set("answers", json.read(r.getAnswers()));
        ObjectNode gift = json.object();
        gift.put("awarded", r.isGiftAwarded());
        if (r.getGiftAwardedAt() != null) {
            gift.put("awardedAt", r.getGiftAwardedAt().toString());
        }
        if (r.getGiftAwardedBy() != null) {
            gift.put("by", r.getGiftAwardedBy());
        }
        // itemCode — сводка через запятую (совместимость), itemCodes — все активные коды маркировки
        if (r.getGiftItemCode() != null) {
            gift.put("itemCode", r.getGiftItemCode());
        }
        ArrayNode codes = gift.putArray("itemCodes");
        for (GiftItem gi : giftItems.findAllByResponseIdAndActiveTrueOrderByAddedAt(r.getId())) {
            codes.add(gi.getCode());
        }
        node.set("gift", gift);
        return node;
    }
    /** Формат совместим с приёмом gift в 1С, тип записи явно отделяет выдачу от анкеты. */
    public ObjectNode exportManualGiftView(ManualGiftAward award) {
        ObjectNode node = json.object();
        node.put("recordType", "manual_gift");
        node.put("seq", award.getChangeSeq());
        node.put("id", award.getId().toString());
        node.put("phone", "");
        node.put("submittedAt", award.getAt().toString());
        node.put("questionnaireVersion", 0);
        node.set("answers", json.object());
        ObjectNode gift = node.putObject("gift");
        gift.put("awarded", true);
        gift.put("awardedAt", award.getAt().toString());
        gift.put("by", award.getByUser());
        JsonNode codes = json.read(award.getItemCodes());
        gift.set("itemCodes", codes);
        List<String> summary = new ArrayList<>();
        codes.forEach(code -> summary.add(code.asString()));
        if (!summary.isEmpty()) {
            gift.put("itemCode", String.join(", ", summary));
        }
        return node;
    }

}
