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
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.config.SurveyProperties;
import ru.big.survey.domain.Event;
import ru.big.survey.domain.Questionnaire;
import ru.big.survey.domain.Response;
import ru.big.survey.domain.SyncState;
import ru.big.survey.persistence.EventRepository;
import ru.big.survey.persistence.QuestionnaireRepository;
import ru.big.survey.persistence.ResponseRepository;
import ru.big.survey.persistence.SyncStateRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Обмен с 1С:ERP (1С — инициатор):
 *  publish  — создать/обновить мероприятие и опубликовать версию анкеты (идемпотентно по checksum);
 *  export   — ответы с change_seq > after (создание и изменения подарка приходят повторно с новым seq);
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
    private final SchemaService schemas;
    private final AuditService audit;
    private final Json json;
    private final SurveyProperties properties;
    private final Clock clock;

    public SyncService(EventRepository events, QuestionnaireRepository questionnaires, ResponseRepository responses,
                       SyncStateRepository states, SchemaService schemas, AuditService audit, Json json,
                       SurveyProperties properties, Clock clock) {
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
                                 Boolean active, JsonNode theme, JsonNode questionnaire) {}

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
                    command.active() != null ? command.active() : true,
                    theme, now);
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

    @Transactional
    public ExportPage export(UUID eventId, long after, int limit, String actor) {
        requireEvent(eventId);
        int size = Math.max(1, Math.min(limit, MAX_PAGE));
        List<Response> page = responses.findChangedAfter(eventId, after, PageRequest.of(0, size + 1));
        boolean hasMore = page.size() > size;
        if (hasMore) {
            page = page.subList(0, size);
        }
        List<ObjectNode> items = new ArrayList<>(page.size());
        long last = after;
        for (Response r : page) {
            items.add(exportView(r));
            last = r.getChangeSeq();
        }
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
        node.put("version", event.getCurrentVersion());
        node.put("publicUrl", publicUrl(eventId));
        node.put("responses", responses.countByEventId(eventId));
        node.put("giftsAwarded", responses.countByEventIdAndGiftAwardedTrue(eventId));
        node.put("ackedSeq", acked);
        node.put("pending", responses.countByEventIdAndChangeSeqGreaterThan(eventId, acked));
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
        node.set("gift", gift);
        return node;
    }
}
