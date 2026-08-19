package ru.big.survey.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.big.survey.config.SurveyProperties;
import tools.jackson.databind.JsonNode;

/**
 * zvonok.com: GET {base}flashcall/?public_key=…&campaign_id=…&phone=7XXXXXXXXXX
 *   успех: {"status":"ok","data":{"pincode":1234,...}}   ошибка: {"status":"error","data":"текст"}
 * Провайдер по умолчанию (survey.flash-call.provider=zvonok). Для теста без звонков — StubFlashCallClient.
 */
@Component
public class ZvonokFlashCallClient implements FlashCallClient {

    private static final Logger log = LoggerFactory.getLogger(ZvonokFlashCallClient.class);

    private final SurveyProperties properties;
    private final Json json;
    private final HttpClient http;

    public ZvonokFlashCallClient(SurveyProperties properties, Json json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String call(String phone) {
        SurveyProperties.FlashCall cfg = properties.getFlashCall();
        if (cfg.getPublicKey().isBlank() || cfg.getCampaignId().isBlank()) {
            log.error("Не заданы survey.flash-call.public-key / campaign-id");
            throw ApiException.upstream("Сервис звонков не настроен. Обратитесь к администратору.");
        }
        String base = cfg.getBaseUrl().endsWith("/") ? cfg.getBaseUrl() : cfg.getBaseUrl() + "/";
        URI uri = URI.create(base + "flashcall/?public_key=" + enc(cfg.getPublicKey())
                + "&campaign_id=" + enc(cfg.getCampaignId()) + "&phone=" + enc(phone));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(cfg.getTimeout()).GET().build();
        String body;
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            body = response.body();
            if (response.statusCode() >= 500) {
                log.error("zvonok.com HTTP {} для {}: {}", response.statusCode(), Phones.mask(phone), abbreviate(body));
                throw ApiException.upstream("Сервис звонков недоступен. Попробуйте позже.");
            }
        } catch (java.io.IOException e) {
            log.error("zvonok.com недоступен для {}: {}", Phones.mask(phone), e.toString());
            throw ApiException.upstream("Сервис звонков недоступен. Попробуйте позже.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.upstream("Сервис звонков недоступен. Попробуйте позже.");
        }
        return parseCode(body, phone);
    }

    String parseCode(String body, String phone) {
        JsonNode data;
        try {
            data = json.read(body);
        } catch (RuntimeException e) {
            log.error("zvonok.com: некорректный JSON для {}: {}", Phones.mask(phone), abbreviate(body));
            throw ApiException.upstream("Некорректный ответ сервиса звонков.");
        }
        if ("error".equals(data.path("status").asString(""))) {
            String reason = data.path("data").isString() ? data.path("data").stringValue() : abbreviate(body);
            log.error("zvonok.com отклонил запрос для {}: {}", Phones.mask(phone), reason);
            if (reason.toLowerCase().contains("phone")) {
                throw ApiException.badRequest("phone", "Сервис звонков не принял номер телефона. Проверьте номер.");
            }
            throw ApiException.upstream("Сервис звонков отклонил запрос: " + abbreviate(reason));
        }
        JsonNode pin = data.path("data").path("pincode");
        if (pin.isMissingNode() || pin.isNull()) {
            log.error("zvonok.com: нет pincode для {}: {}", Phones.mask(phone), abbreviate(body));
            throw ApiException.upstream("Не получен код для подтверждения.");
        }
        String code = pin.isNumber() ? String.format("%04d", pin.asInt()) : pin.asString().trim();
        if (code.isEmpty()) {
            throw ApiException.upstream("Не получен код для подтверждения.");
        }
        return code;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
