package ru.big.survey.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.big.survey.config.SurveyProperties;
import tools.jackson.databind.JsonNode;

/**
 * Проверка кода в «Честном знаке» через True API:
 *   POST {base}/api/v3/true-api/cises/info, заголовок Authorization: Bearer &lt;токен&gt;,
 *   тело — массив КИ (код идентификации БЕЗ крипто-хвоста 93xxxx).
 * Ровно так работает типовая обработка ERP «Проверка кодов маркировки ГИС МТ» (сверено
 * по её логу запросов 31.08.2026). Признак «в обороте»: cisInfo.status == INTRODUCED.
 * Токен True API живёт ~10 часов; его получает 1С (там настроена УКЭП) и передаёт сервису
 * через PUT /api/v1/sync/marking-token; на время отладки токен можно задать в конфиге.
 * Важно про заголовки: этот же токен в X-API-KEY (CDN codes/check) и clientToken
 * даёт 401 — работает именно Bearer. Публичная анонимная проверка закрыта ЧЗ (451).
 */
@Component
public class CrptMarkingCheckClient implements MarkingCheckClient {

    private static final Logger log = LoggerFactory.getLogger(CrptMarkingCheckClient.class);

    private final SurveyProperties properties;
    private final Json json;
    private final HttpClient http;
    /** Токен, доставленный 1С (приоритетнее конфига). */
    private final AtomicReference<DeliveredToken> deliveredToken = new AtomicReference<>();

    public record DeliveredToken(String token, Instant expiresAt) {}

    public CrptMarkingCheckClient(SurveyProperties properties, Json json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Принять свежий токен True API от 1С. Срок: минимум из переданного и exp внутри JWT. */
    public void acceptToken(String token, Instant expiresAt) {
        Instant jwtExp = jwtExpiry(token);
        Instant effective = expiresAt;
        if (jwtExp != null && (effective == null || jwtExp.isBefore(effective))) {
            effective = jwtExp;
        }
        deliveredToken.set(new DeliveredToken(token, effective));
    }

    /** exp из payload JWT (unix-секунды); null, если токен не JWT или поле отсутствует. */
    static Instant jwtExpiry(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            String text = new String(payload, StandardCharsets.UTF_8);
            int idx = text.indexOf("\"exp\"");
            if (idx < 0) {
                return null;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"exp\"\\s*:\\s*(\\d+)").matcher(text);
            return m.find() ? Instant.ofEpochSecond(Long.parseLong(m.group(1))) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Активный токен: доставленный 1С (если не истёк), иначе из конфига. Пустая строка — токена нет. */
    String activeToken(Instant now) {
        DeliveredToken delivered = deliveredToken.get();
        if (delivered != null && (delivered.expiresAt() == null || delivered.expiresAt().isAfter(now))) {
            return delivered.token();
        }
        return properties.getMarkingCheck().getToken();
    }

    /**
     * КИ (код идентификации) из полного кода маркировки: крипто-хвост (группа 93) отрезается —
     * cises/info принимает только КИ. Короткий формат: 01+GTIN(14)+21+серийник(6) [+93+4] → 24 символа;
     * длинный: 01+GTIN(14)+21+серийник(13) [+93...] → 31 символ. Разделитель GS к этому моменту
     * уже вычищен (GiftService.normalizeItemCode), поэтому режем по позиции литерала «93».
     */
    static String cisKey(String code) {
        int gs = code.indexOf('\u001d');
        if (gs > 0) {
            return code.substring(0, gs);
        }
        if (code.length() >= 26 && code.startsWith("01") && code.startsWith("21", 16) && code.startsWith("93", 24)) {
            return code.substring(0, 24);
        }
        if (code.length() >= 33 && code.startsWith("01") && code.startsWith("21", 16) && code.startsWith("93", 31)) {
            return code.substring(0, 31);
        }
        return code;
    }

    @Override
    public Verdict check(String code) {
        SurveyProperties.MarkingCheck cfg = properties.getMarkingCheck();
        String token = activeToken(Instant.now());
        if (token == null || token.isBlank()) {
            DeliveredToken delivered = deliveredToken.get();
            return Verdict.unknown(delivered == null
                    ? "Токен «Честного знака» не задан (ожидается доставка из 1С)."
                    : "Токен «Честного знака» истёк " + delivered.expiresAt() + " (1С не прислала свежий).");
        }
        String base = cfg.getBaseUrl().endsWith("/")
                ? cfg.getBaseUrl().substring(0, cfg.getBaseUrl().length() - 1)
                : cfg.getBaseUrl();
        String payload = json.write(java.util.List.of(cisKey(code)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v3/true-api/cises/info"))
                .timeout(cfg.getTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        String body;
        int status;
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            status = response.statusCode();
            body = response.body();
        } catch (java.io.IOException e) {
            log.warn("ЧЗ недоступен для {}: {}", shortCode(code), e.toString());
            return Verdict.unknown("Сервис «Честный знак» недоступен: " + briefCause(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Verdict.unknown("Сервис «Честный знак» недоступен.");
        }
        if (status == 401 || status == 403) {
            log.warn("ЧЗ отверг токен (HTTP {}) для {}", status, shortCode(code));
            return Verdict.unknown("Токен «Честного знака» устарел или неверен.");
        }
        if (status == 404) {
            return Verdict.rejected("Код не найден в «Честном знаке».");
        }
        if (status != 200) {
            log.warn("ЧЗ HTTP {} для {}: {}", status, shortCode(code), abbreviate(body));
            return Verdict.unknown("Сервис «Честный знак» ответил " + status + ".");
        }
        return parse(body, code);
    }

    /**
     * Разбор ответа cises/info: [{"cisInfo":{"status":"INTRODUCED",...}}].
     * Статусы ГИС МТ: EMITTED/APPLIED — не введён в оборот, INTRODUCED — в обороте,
     * RETIRED/WITHDRAWN/WRITTEN_OFF — выведен из оборота, DISAGGREGATED — расформирован.
     * Вынесен для юнит-тестов.
     */
    Verdict parse(String body, String code) {
        JsonNode data;
        try {
            data = json.read(body);
        } catch (RuntimeException e) {
            log.warn("ЧЗ: некорректный JSON для {}: {}", shortCode(code), abbreviate(body));
            return Verdict.unknown("Ответ «Честного знака» не разобран.");
        }
        JsonNode item = data.path(0);
        if (item.isMissingNode() || item.isNull()) {
            log.warn("ЧЗ: пустой ответ для {}: {}", shortCode(code), abbreviate(body));
            return Verdict.unknown("Ответ «Честного знака» не разобран.");
        }
        String error = item.path("errorMessage").asString(item.path("error_message").asString(""));
        if (!error.isBlank()) {
            log.info("ЧЗ: ошибка по коду {}: {}", shortCode(code), error);
            return Verdict.rejected("«Честный знак»: " + error);
        }
        JsonNode cisInfo = item.path("cisInfo");
        String cisStatus = cisInfo.path("status").asString("");
        if (cisStatus.isBlank()) {
            log.warn("ЧЗ: нет статуса для {}: {}", shortCode(code), abbreviate(body));
            return Verdict.unknown("Ответ «Честного знака» не разобран.");
        }
        switch (cisStatus) {
            case "INTRODUCED":
                return Verdict.ok();
            case "EMITTED":
            case "APPLIED":
                return Verdict.rejected("Код не введён в оборот (статус " + cisStatus + ").");
            case "RETIRED":
            case "WITHDRAWN":
            case "WRITTEN_OFF":
                log.info("ЧЗ: код {} выведен из оборота: {}", shortCode(code), cisStatus);
                return Verdict.rejected("Код уже выведен из оборота (статус " + cisStatus + ").");
            default:
                log.info("ЧЗ: код {} не в обороте, статус {}", shortCode(code), cisStatus);
                return Verdict.rejected("Код не в обороте (статус " + cisStatus + ").");
        }
    }

    /** Короткая причина сетевой ошибки для вердикта/журнала: класс + сообщение. */
    private static String briefCause(Exception e) {
        Throwable t = e.getCause() != null ? e.getCause() : e;
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : " (" + abbreviate(msg) + ")");
    }

    /** Код в логи целиком не пишем. */
    private static String shortCode(String c) {
        return c.length() > 20 ? c.substring(0, 16) + "…" : c;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
