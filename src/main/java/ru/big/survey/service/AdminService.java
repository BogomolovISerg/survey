package ru.big.survey.service;

import java.io.IOException;
import java.io.Writer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.domain.Event;
import ru.big.survey.domain.Questionnaire;
import ru.big.survey.domain.Response;
import ru.big.survey.domain.SyncLog;
import ru.big.survey.domain.SyncState;
import ru.big.survey.persistence.EventRepository;
import ru.big.survey.persistence.QuestionnaireRepository;
import ru.big.survey.persistence.ResponseRepository;
import ru.big.survey.persistence.SyncLogRepository;
import ru.big.survey.persistence.SyncStateRepository;
import ru.big.survey.security.Actor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Панель стенда (счётчики) и админка (мероприятия, ответы, CSV, журнал). */
@Service
public class AdminService {

    private static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZONE);

    private final EventRepository events;
    private final QuestionnaireRepository questionnaires;
    private final ResponseRepository responses;
    private final SyncStateRepository states;
    private final SyncLogRepository logs;
    private final AuditService audit;
    private final SyncService sync;
    private final Json json;
    private final Clock clock;

    public AdminService(EventRepository events, QuestionnaireRepository questionnaires, ResponseRepository responses,
                        SyncStateRepository states, SyncLogRepository logs, AuditService audit, SyncService sync,
                        Json json, Clock clock) {
        this.events = events;
        this.questionnaires = questionnaires;
        this.responses = responses;
        this.states = states;
        this.logs = logs;
        this.audit = audit;
        this.sync = sync;
        this.json = json;
        this.clock = clock;
    }

    // ---------- стенд ----------

    /** Текущие мероприятия для панели стенда: активные, идущие сейчас (±7 дней) либо без дат. */
    @Transactional(readOnly = true)
    public List<ObjectNode> currentEvents() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZONE);
        List<ObjectNode> out = new ArrayList<>();
        for (Event e : events.findCurrent(today.minusDays(7), today.plusDays(7))) {
            out.add(eventSummary(e, false));
        }
        return out;
    }

    /** Живые счётчики мероприятия. */
    @Transactional(readOnly = true)
    public ObjectNode stats(UUID eventId) {
        Event event = requireEvent(eventId);
        Instant now = clock.instant();
        ObjectNode s = json.object();
        s.put("eventId", eventId.toString());
        s.put("eventName", event.getName());
        s.put("giftEnabled", event.isGiftEnabled());
        s.put("total", responses.countByEventId(eventId));
        s.put("lastHour", responses.countByEventIdAndSubmittedAtAfter(eventId, now.minusSeconds(3600)));
        s.put("today", responses.countByEventIdAndSubmittedAtAfter(eventId,
                LocalDate.ofInstant(now, ZONE).atStartOfDay(ZONE).toInstant()));
        s.put("giftsAwarded", responses.countByEventIdAndGiftAwardedTrue(eventId));
        ArrayNode recent = s.putArray("recent");
        for (Response r : responses.findTop10ByEventIdOrderBySubmittedAtDesc(eventId)) {
            JsonNode answers = json.read(r.getAnswers());
            ObjectNode item = recent.addObject();
            item.put("responseId", r.getId().toString());
            item.put("visitor", GiftService.visitorName(answers));
            item.put("city", GiftService.text(answers, "Город"));
            item.put("submittedAt", r.getSubmittedAt().toString());
            item.put("awarded", r.isGiftAwarded());
        }
        s.put("at", now.toString());
        return s;
    }

    // ---------- админка ----------

    @Transactional(readOnly = true)
    public List<ObjectNode> allEvents() {
        List<ObjectNode> out = new ArrayList<>();
        for (Event e : events.findAllByOrderByPublishedAtDesc()) {
            out.add(eventSummary(e, true));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public ObjectNode eventDetails(UUID eventId) {
        Event e = requireEvent(eventId);
        ObjectNode node = eventSummary(e, true);
        ArrayNode versions = node.putArray("versions");
        for (Questionnaire q : questionnaires.findAllByEventIdOrderByVersionDesc(eventId)) {
            ObjectNode v = versions.addObject();
            v.put("version", q.getVersion());
            v.put("publishedAt", q.getPublishedAt().toString());
            v.put("checksum", q.getChecksum());
        }
        return node;
    }

    @Transactional(readOnly = true)
    public JsonNode questionnaire(UUID eventId, Integer version) {
        Event e = requireEvent(eventId);
        int v = version == null ? e.getCurrentVersion() : version;
        return questionnaires.findByEventIdAndVersion(eventId, v)
                .map(q -> json.read(q.getSchema()))
                .orElseThrow(() -> ApiException.notFound("Версия анкеты не найдена."));
    }

    @Transactional
    public ObjectNode setActive(UUID eventId, boolean active, Actor actor) {
        Event e = requireEvent(eventId);
        e.setActive(active, clock.instant());
        events.save(e);
        audit.ok(eventId, "EVENT", actor.username(), Map.of("action", active ? "activate" : "deactivate"));
        return eventSummary(e, true);
    }

    @Transactional(readOnly = true)
    public ObjectNode responsesPage(UUID eventId, int page, int size) {
        Event event = requireEvent(eventId);
        Page<Response> p = responses.findAllByEventIdOrderBySubmittedAtDesc(eventId, PageRequest.of(page, Math.min(size, 200)));
        ObjectNode node = json.object();
        node.put("total", p.getTotalElements());
        node.put("page", page);
        node.put("size", p.getSize());
        ArrayNode items = node.putArray("items");
        for (Response r : p.getContent()) {
            ObjectNode item = items.addObject();
            item.put("id", r.getId().toString());
            item.put("phone", r.getPhone());
            item.put("submittedAt", r.getSubmittedAt().toString());
            item.put("version", r.getQuestionnaireVersion());
            item.put("giftAwarded", r.isGiftAwarded());
            if (r.getGiftAwardedAt() != null) {
                item.put("giftAwardedAt", r.getGiftAwardedAt().toString());
                item.put("giftAwardedBy", r.getGiftAwardedBy());
            }
            item.put("giftCode", event.isGiftEnabled() ? r.getGiftCode() : "");
            item.put("changeSeq", r.getChangeSeq());
            item.set("answers", json.read(r.getAnswers()));
        }
        return node;
    }

    /** CSV (UTF-8 с BOM, разделитель ';') — все ответы мероприятия; колонки — объединение ключей ответов. */
    @Transactional(readOnly = true)
    public void writeCsv(UUID eventId, Writer out, Actor actor) throws IOException {
        Event event = requireEvent(eventId);
        List<Response> all = responses.findAllByEventIdOrderBySubmittedAtAsc(eventId);
        // порядок колонок — как в output последней версии анкеты (jsonb порядок ключей не хранит), затем прочие
        Set<String> columns = new LinkedHashSet<>();
        questionnaires.findByEventIdAndVersion(eventId, event.getCurrentVersion())
                .ifPresent(q -> columns.addAll(json.read(q.getSchema()).path("output").propertyNames()));
        List<JsonNode> parsed = new ArrayList<>(all.size());
        for (Response r : all) {
            JsonNode a = json.read(r.getAnswers());
            parsed.add(a);
            columns.addAll(a.propertyNames());
        }
        out.write('\uFEFF'); // BOM — чтобы Excel открыл UTF-8 корректно
        List<String> header = new ArrayList<>(List.of("Дата", "Идентификатор", "Телефон", "Версия", "Подарок", "Подарок выдан", "Кем"));
        header.addAll(columns);
        writeRow(out, header);
        for (int i = 0; i < all.size(); i++) {
            Response r = all.get(i);
            JsonNode a = parsed.get(i);
            List<String> row = new ArrayList<>();
            row.add(CSV_TIME.format(r.getSubmittedAt()));
            row.add(r.getId().toString());
            row.add(r.getPhone());
            row.add(String.valueOf(r.getQuestionnaireVersion()));
            row.add(r.isGiftAwarded() ? "да" : "нет");
            row.add(r.getGiftAwardedAt() == null ? "" : CSV_TIME.format(r.getGiftAwardedAt()));
            row.add(r.getGiftAwardedBy() == null ? "" : r.getGiftAwardedBy());
            for (String c : columns) {
                JsonNode v = a.get(c);
                row.add(v == null || v.isNull() ? "" : (v.isString() ? v.stringValue() : v.toString()));
            }
            writeRow(out, row);
        }
        out.flush();
        audit.ok(eventId, "CSV", actor.username(), Map.of("rows", all.size(), "event", event.getName()));
    }

    private static void writeRow(Writer out, List<String> cells) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            String c = cells.get(i) == null ? "" : cells.get(i);
            boolean quote = c.contains(";") || c.contains("\"") || c.contains("\n") || c.contains("\r");
            if (quote) {
                sb.append('"').append(c.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(c);
            }
        }
        sb.append("\r\n");
        out.write(sb.toString());
    }

    @Transactional(readOnly = true)
    public ObjectNode log(UUID eventId, int page, int size) {
        Page<SyncLog> p = eventId == null
                ? logs.findAllByOrderByAtDesc(PageRequest.of(page, Math.min(size, 200)))
                : logs.findAllByEventIdOrderByAtDesc(eventId, PageRequest.of(page, Math.min(size, 200)));
        ObjectNode node = json.object();
        node.put("total", p.getTotalElements());
        node.put("page", page);
        ArrayNode items = node.putArray("items");
        for (SyncLog l : p.getContent()) {
            ObjectNode item = items.addObject();
            item.put("id", l.getId().toString());
            item.put("at", l.getAt().toString());
            item.put("kind", l.getKind());
            item.put("status", l.getStatus());
            item.put("actor", l.getActor());
            if (l.getEventId() != null) {
                item.put("eventId", l.getEventId().toString());
            }
            item.set("details", json.read(l.getDetails()));
        }
        return node;
    }

    // ---------- общее ----------

    private Event requireEvent(UUID eventId) {
        return events.findById(eventId).orElseThrow(() -> ApiException.notFound("Мероприятие не найдено."));
    }

    ObjectNode eventSummary(Event e, boolean withSync) {
        ObjectNode n = json.object();
        n.put("id", e.getId().toString());
        n.put("name", e.getName());
        if (e.getStartsOn() != null) {
            n.put("startsOn", e.getStartsOn().toString());
        }
        if (e.getEndsOn() != null) {
            n.put("endsOn", e.getEndsOn().toString());
        }
        n.put("giftEnabled", e.isGiftEnabled());
        n.put("active", e.isActive());
        n.put("version", e.getCurrentVersion());
        n.put("publishedAt", e.getPublishedAt().toString());
        n.put("publicUrl", sync.publicUrl(e.getId()));
        if (e.getTheme() != null) {
            n.set("theme", json.read(e.getTheme()));
        }
        if (withSync) {
            n.put("responses", responses.countByEventId(e.getId()));
            n.put("giftsAwarded", responses.countByEventIdAndGiftAwardedTrue(e.getId()));
            SyncState s = states.findById(e.getId()).orElse(null);
            long acked = s == null ? 0 : s.getAckedSeq();
            n.put("ackedSeq", acked);
            n.put("pending", responses.countByEventIdAndChangeSeqGreaterThan(e.getId(), acked));
            if (s != null && s.getLastExportAt() != null) {
                n.put("lastExportAt", s.getLastExportAt().toString());
            }
            if (s != null && s.getLastAckAt() != null) {
                n.put("lastAckAt", s.getLastAckAt().toString());
            }
        }
        return n;
    }
}
