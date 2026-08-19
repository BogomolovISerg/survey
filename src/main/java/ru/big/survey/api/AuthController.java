package ru.big.survey.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.big.survey.domain.AppUser;
import ru.big.survey.service.AuditService;
import ru.big.survey.service.UserService;

/** Вход/выход персонала и администратора: сессия в cookie. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final UserService users;
    private final AuditService audit;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository securityContextRepository,
                          UserService users, AuditService audit) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.users = users;
        this.audit = audit;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request, HttpServletRequest http, HttpServletResponse response) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username().trim(), request.password()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // новая сессия после входа — защита от фиксации сессии
        if (http.getSession(false) != null) {
            http.changeSessionId();
        }
        securityContextRepository.saveContext(context, http, response);
        AppUser user = users.require(authentication.getName());
        audit.ok(null, "LOGIN", user.getUsername(), Map.of("roles", user.getRoles().toString()));
        return userView(user);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthorized", "message", "Требуется вход."));
        }
        return ResponseEntity.ok(userView(users.require(authentication.getName())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        SecurityContextHolder.clearContext();
        if (http.getSession(false) != null) {
            http.getSession(false).invalidate();
        }
        return ResponseEntity.noContent().build();
    }

    static Map<String, Object> userView(AppUser user) {
        return Map.of(
                "id", user.getId().toString(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "roles", user.getRoles().stream().map(Enum::name).sorted().toList(),
                "active", user.isActive());
    }
}
