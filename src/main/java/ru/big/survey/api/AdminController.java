package ru.big.survey.api;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.big.survey.domain.AppUser;
import ru.big.survey.domain.Role;
import ru.big.survey.security.Actor;
import ru.big.survey.service.AdminService;
import ru.big.survey.service.UserService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Админка (роль ADMIN): мероприятия, ответы, CSV, журнал, пользователи. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService admin;
    private final UserService users;

    public AdminController(AdminService admin, UserService users) {
        this.admin = admin;
        this.users = users;
    }

    @GetMapping("/events")
    public List<ObjectNode> events() {
        return admin.allEvents();
    }

    @GetMapping("/events/{eventId}")
    public ObjectNode event(@PathVariable UUID eventId) {
        return admin.eventDetails(eventId);
    }

    @GetMapping("/events/{eventId}/questionnaire")
    public JsonNode questionnaire(@PathVariable UUID eventId, @RequestParam(required = false) Integer version) {
        return admin.questionnaire(eventId, version);
    }

    public record ActiveRequest(boolean active) {}

    @PatchMapping("/events/{eventId}")
    public ObjectNode setActive(@PathVariable UUID eventId, @RequestBody ActiveRequest request, Authentication auth) {
        return admin.setActive(eventId, request.active(), Actor.of(auth));
    }

    @GetMapping("/events/{eventId}/responses")
    public ObjectNode responses(@PathVariable UUID eventId,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        return admin.responsesPage(eventId, page, size);
    }

    @GetMapping(value = "/events/{eventId}/export.csv", produces = "text/csv")
    public void csv(@PathVariable UUID eventId, Authentication auth, HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"survey-" + eventId + ".csv\"");
        try (Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            admin.writeCsv(eventId, out, Actor.of(auth));
        }
    }

    @GetMapping("/log")
    public ObjectNode log(@RequestParam(required = false) UUID eventId,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "50") int size) {
        return admin.log(eventId, page, size);
    }

    // ---------- пользователи ----------

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return users.list().stream().map(AuthController::userView).toList();
    }

    public record CreateUserRequest(String username, String displayName, String password, Set<Role> roles) {}

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody CreateUserRequest request, Authentication auth) {
        AppUser user = users.create(request.username(), request.displayName(), request.password(), request.roles(), Actor.of(auth));
        return AuthController.userView(user);
    }

    public record UpdateUserRequest(String displayName, String password, Set<Role> roles, Boolean active) {}

    @PatchMapping("/users/{id}")
    public Map<String, Object> updateUser(@PathVariable UUID id, @RequestBody UpdateUserRequest request, Authentication auth) {
        AppUser user = users.update(id, request.displayName(), request.password(), request.roles(), request.active(), Actor.of(auth));
        return AuthController.userView(user);
    }
}
