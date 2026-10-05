package ru.big.survey.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.big.survey.service.ApiException;
import ru.big.survey.service.AuditService;
import ru.big.survey.service.CrptMarkingCheckClient;

/**
 * Доставка токена True API «Честного знака» из 1С (роль INTEGRATION, HTTP Basic):
 *   PUT /api/v1/sync/marking-token {"token":"…","expiresAt":"2026-08-26T18:00:00Z"}
 * Токен получает 1С по УКЭП (там настроена ИСМП-интеграция) и обновляет с запасом
 * до истечения (срок жизни токена ЧЗ ~10 часов). Сервис проверяет им коды при сканировании.
 */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncMarkingTokenController {

    private final CrptMarkingCheckClient crpt;
    private final AuditService audit;

    public SyncMarkingTokenController(CrptMarkingCheckClient crpt, AuditService audit) {
        this.crpt = crpt;
        this.audit = audit;
    }

    public record TokenRequest(@NotBlank String token, String expiresAt) {}

    @PutMapping("/marking-token")
    public Map<String, Object> put(@Valid @RequestBody TokenRequest request, Authentication auth) {
        Instant expiresAt = null;
        if (request.expiresAt() != null && !request.expiresAt().isBlank()) {
            try {
                expiresAt = Instant.parse(request.expiresAt());
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.badRequest("expiresAt", "Ожидается дата в формате ISO-8601, например 2026-08-26T18:00:00Z.");
            }
        }
        crpt.acceptToken(request.token(), expiresAt);
        audit.ok(null, "MARKING_TOKEN", auth.getName(), Map.of("expiresAt", String.valueOf(expiresAt)));
        return Map.of("accepted", true);
    }
}
