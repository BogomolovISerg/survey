package ru.big.survey.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.big.survey.service.Json;
import ru.big.survey.service.PhoneVerificationService;
import ru.big.survey.service.ResponseService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Публичный API анкеты (без авторизации; защита — токены и лимиты).
 *   GET  /api/v1/public/events/{guid}                    — схема анкеты
 *   POST /api/v1/public/events/{guid}/phone/call         — заказать flash-call {phone}
 *   POST /api/v1/public/events/{guid}/phone/verify       — проверить код {phone, code} → {token}
 *   POST /api/v1/public/events/{guid}/responses          — отправить ответ {token, answers, consent}
 *   GET  /api/v1/public/events/{guid}/gift?token=        — статус подарка и данные QR
 */
@RestController
@RequestMapping("/api/v1/public/events")
public class PublicController {

    private final ResponseService responseService;
    private final PhoneVerificationService verification;
    private final Json json;

    public PublicController(ResponseService responseService, PhoneVerificationService verification, Json json) {
        this.responseService = responseService;
        this.verification = verification;
        this.json = json;
    }

    @GetMapping("/{eventId}")
    public ObjectNode schema(@PathVariable UUID eventId) {
        return responseService.publicSchema(eventId);
    }

    public record PhoneRequest(@NotBlank String phone) {}

    @PostMapping("/{eventId}/phone/call")
    public Map<String, Object> call(@PathVariable UUID eventId, @RequestBody PhoneRequest request) {
        PhoneVerificationService.CallResult r = verification.call(request.phone());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", r.status());
        body.put("message", r.message());
        body.put("ttl", r.ttlSeconds());
        body.put("attempts", r.attempts());
        body.put("retryAfter", r.retryAfterSeconds());
        if (r.token() != null) {
            body.put("token", r.token());
        }
        return body;
    }

    public record VerifyRequest(@NotBlank String phone, @NotBlank String code) {}

    @PostMapping("/{eventId}/phone/verify")
    public Map<String, Object> verify(@PathVariable UUID eventId, @RequestBody VerifyRequest request) {
        PhoneVerificationService.VerifyResult r = verification.verify(request.phone(), request.code());
        return Map.of("verified", true, "token", r.token(), "validUntil", r.validUntil().toString());
    }

    public record SubmitRequest(@NotBlank String token, JsonNode answers, boolean consent) {}

    @PostMapping("/{eventId}/responses")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable UUID eventId, @RequestBody SubmitRequest request,
                                                      HttpServletRequest http) {
        ObjectNode client = json.object();
        client.put("userAgent", abbreviate(http.getHeader("User-Agent")));
        client.put("ip", clientIp(http));
        ResponseService.SubmitResult r = responseService.submit(eventId, request.token(), request.answers(), request.consent(),
                json.write(client));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "responseId", r.responseId().toString(),
                "gift", r.gift()));
    }

    @GetMapping("/{eventId}/gift")
    public ObjectNode gift(@PathVariable UUID eventId, @RequestParam String token) {
        return responseService.giftStatus(eventId, token);
    }

    private static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
