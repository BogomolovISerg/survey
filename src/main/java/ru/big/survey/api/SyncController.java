package ru.big.survey.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.big.survey.service.SyncService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Интеграционный API для 1С:ERP (роль INTEGRATION, HTTP Basic).
 *   PUT  /api/v1/sync/events/{guid}                      — публикация мероприятия и анкеты
 *   GET  /api/v1/sync/events/{guid}/responses?after=&limit= — ответы с change_seq > after
 *   POST /api/v1/sync/events/{guid}/responses/ack {seq}  — подтверждение курсора
 *   GET  /api/v1/sync/events/{guid}/status               — счётчики
 */
@RestController
@RequestMapping("/api/v1/sync/events")
public class SyncController {

    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    public record PublishRequest(@NotBlank String name, LocalDate startsOn, LocalDate endsOn, Boolean giftEnabled,
                                 Boolean active, JsonNode theme, JsonNode questionnaire) {}

    @PutMapping("/{eventId}")
    public Map<String, Object> publish(@PathVariable UUID eventId, @Valid @RequestBody PublishRequest request, Authentication auth) {
        SyncService.PublishResult result = sync.publish(eventId, new SyncService.PublishCommand(
                request.name(), request.startsOn(), request.endsOn(), request.giftEnabled(), request.active(),
                request.theme(), request.questionnaire()), auth.getName());
        return Map.of(
                "eventId", result.eventId().toString(),
                "version", result.version(),
                "changed", result.changed(),
                "publicUrl", result.publicUrl(),
                "publishedAt", result.publishedAt().toString());
    }

    @GetMapping("/{eventId}/responses")
    public Map<String, Object> responses(@PathVariable UUID eventId,
                                         @RequestParam(defaultValue = "0") long after,
                                         @RequestParam(defaultValue = "500") int limit,
                                         Authentication auth) {
        SyncService.ExportPage page = sync.export(eventId, after, limit, auth.getName());
        return Map.of(
                "items", page.items(),
                "nextAfter", page.nextAfter(),
                "hasMore", page.hasMore(),
                "ackedSeq", page.ackedSeq());
    }

    public record AckRequest(long seq) {}

    @PostMapping("/{eventId}/responses/ack")
    public ResponseEntity<Map<String, Object>> ack(@PathVariable UUID eventId, @RequestBody AckRequest request, Authentication auth) {
        long acked = sync.ack(eventId, request.seq(), auth.getName());
        return ResponseEntity.ok(Map.of("ackedSeq", acked));
    }

    @GetMapping("/{eventId}/status")
    public ObjectNode status(@PathVariable UUID eventId) {
        return sync.status(eventId);
    }
}
