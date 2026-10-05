package ru.big.survey.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.config.SurveyProperties;
import ru.big.survey.domain.Event;
import ru.big.survey.domain.Questionnaire;
import ru.big.survey.domain.Response;
import ru.big.survey.persistence.EventRepository;
import ru.big.survey.persistence.QuestionnaireRepository;
import ru.big.survey.persistence.ResponseRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Публичная сторона: схема анкеты, приём ответа, статус подарка посетителя. */
@Service
public class ResponseService {

    private static final Logger log = LoggerFactory.getLogger(ResponseService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EventRepository events;
    private final QuestionnaireRepository questionnaires;
    private final ResponseRepository responses;
    private final SchemaService schemas;
    private final PhoneVerificationService verification;
    private final TokenService tokens;
    private final Json json;
    private final SurveyProperties properties;
    private final Clock clock;

    public ResponseService(EventRepository events, QuestionnaireRepository questionnaires, ResponseRepository responses,
                           SchemaService schemas, PhoneVerificationService verification, TokenService tokens, Json json,
                           SurveyProperties properties, Clock clock) {
        this.events = events;
        this.questionnaires = questionnaires;
        this.responses = responses;
        this.schemas = schemas;
        this.verification = verification;
        this.tokens = tokens;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    /** Схема анкеты для клиента (последняя версия) + блок event. 404 — нет мероприятия, 410 — закрыто. */
    @Transactional(readOnly = true)
    public ObjectNode publicSchema(UUID eventId) {
        Event event = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Анкета не найдена. Отсканируйте QR-код ещё раз."));
        if (!event.acceptsResponses()) {
            throw ApiException.gone("Мероприятие завершено, анкета закрыта.");
        }
        Questionnaire q = questionnaires.findByEventIdAndVersion(eventId, event.getCurrentVersion())
                .orElseThrow(() -> ApiException.notFound("Анкета мероприятия не опубликована."));
        ObjectNode view = schemas.publicView(json.read(q.getSchema()), eventView(event));
        view.put("version", q.getVersion());
        return view;
    }

    public ObjectNode eventView(Event event) {
        ObjectNode e = json.object();
        e.put("id", event.getId().toString());
        e.put("name", event.getName());
        e.put("gift", event.isGiftEnabled());
        e.put("active", event.isActive());
        if (event.getTheme() != null) {
            e.set("theme", json.read(event.getTheme()));
        }
        return e;
    }

    public record SubmitResult(UUID responseId, boolean created, ObjectNode gift) {}

    /**
     * Приём ответа. Телефон берётся из токена верификации. Один ответ на телефон в рамках мероприятия:
     * повторная отправка → 409 с данными подарка (клиент показывает «анкета уже заполнена»).
     */
    @Transactional
    public SubmitResult submit(UUID eventId, String token, JsonNode answers, boolean consent, String clientJson) {
        String phone = verification.phoneFromToken(token);
        Event event = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Анкета не найдена."));
        if (!event.acceptsResponses()) {
            throw ApiException.gone("Мероприятие завершено, анкета закрыта.");
        }
        if (!consent) {
            throw ApiException.badRequest("consent", "Нужно согласие на обработку персональных данных.");
        }
        if (answers == null || !answers.isObject()) {
            throw ApiException.badRequest("answers", "Некорректная структура ответов.");
        }
        Questionnaire q = questionnaires.findByEventIdAndVersion(eventId, event.getCurrentVersion())
                .orElseThrow(() -> ApiException.notFound("Анкета мероприятия не опубликована."));
        List<String> missing = schemas.missingRequired(json.read(q.getSchema()), answers);
        if (!missing.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "required",
                    "Заполните обязательные поля: " + String.join(", ", missing) + ".", Map.of("fields", missing));
        }
        Response existing = responses.findByEventIdAndPhone(eventId, phone).orElse(null);
        if (existing != null) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "already_submitted",
                    "Анкета с этим номером телефона уже заполнена.",
                    Map.of("responseId", existing.getId().toString(), "gift", giftView(event, existing)));
        }
        // Телефон в ответах приводим к тому, что подтверждён (клиент мог прислать в маске)
        ObjectNode stored = (ObjectNode) answers.deepCopy();
        if (stored.has("Телефон")) {
            stored.put("Телефон", phone);
        }
        Instant now = clock.instant();
        Response saved;
        try {
            saved = responses.save(Response.create(eventId, q.getVersion(), phone, json.write(stored),
                    now, newGiftCode(eventId), clientJson, responses.nextChangeSeq(), now));
        } catch (DataIntegrityViolationException e) {
            // гонка двух отправок с одного номера
            Response race = responses.findByEventIdAndPhone(eventId, phone).orElseThrow(() -> e);
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "already_submitted",
                    "Анкета с этим номером телефона уже заполнена.",
                    Map.of("responseId", race.getId().toString(), "gift", giftView(event, race)));
        }
        log.info("Ответ {} принят: мероприятие {}, телефон {}", saved.getId(), eventId, Phones.mask(phone));
        return new SubmitResult(saved.getId(), true, giftView(event, saved));
    }

    /** Статус подарка по своему номеру (токен верификации) — при повторном открытии анкеты. */
    @Transactional(readOnly = true)
    public ObjectNode giftStatus(UUID eventId, String token) {
        String phone = verification.phoneFromToken(token);
        Event event = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Анкета не найдена."));
        Response r = responses.findByEventIdAndPhone(eventId, phone)
                .orElseThrow(() -> ApiException.notFound("Анкета с этим номером ещё не заполнена."));
        ObjectNode view = giftView(event, r);
        view.put("responseId", r.getId().toString());
        return view;
    }

    /** {enabled, awarded, awardedAt?, giftToken?, giftCode?, giftUrl?} — QR-данные только для мероприятий с подарками. */
    public ObjectNode giftView(Event event, Response r) {
        ObjectNode g = json.object();
        g.put("enabled", event.isGiftEnabled());
        g.put("awarded", r.isGiftAwarded());
        if (r.getGiftAwardedAt() != null) {
            g.put("awardedAt", r.getGiftAwardedAt().toString());
        }
        if (event.isGiftEnabled()) {
            String giftToken = tokens.issueGift(r.getId());
            g.put("giftToken", giftToken);
            g.put("giftCode", r.getGiftCode());
            g.put("giftUrl", properties.getPublicBaseUrl() + "/staff/gift?t=" + giftToken);
        }
        return g;
    }

    private String newGiftCode(UUID eventId) {
        int length = Math.max(4, Math.min(8, properties.getGiftCodeLength()));
        int bound = (int) Math.pow(10, length);
        for (int i = 0; i < 20; i++) {
            String code = String.format("%0" + length + "d", RANDOM.nextInt(bound));
            if (!responses.existsByEventIdAndGiftCode(eventId, code)) {
                return code;
            }
        }
        throw new IllegalStateException("Не удалось подобрать уникальный код подарка");
    }
}
