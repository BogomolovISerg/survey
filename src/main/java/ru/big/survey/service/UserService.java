package ru.big.survey.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.config.SurveyProperties;
import ru.big.survey.domain.AppUser;
import ru.big.survey.domain.Role;
import ru.big.survey.persistence.AppUserRepository;
import ru.big.survey.security.Actor;

/** Локальный реестр пользователей: STAFF / ADMIN / INTEGRATION. */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int MIN_PASSWORD = 8;

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;

    public UserService(AppUserRepository users, PasswordEncoder encoder, AuditService audit, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void bootstrapAdministrator(SurveyProperties.BootstrapAdmin admin) {
        if (users.countByActiveTrue() > 0) {
            return;
        }
        if (admin.getPassword() == null || admin.getPassword().isBlank()) {
            log.warn("Реестр пользователей пуст, а survey.security.bootstrap-admin.password не задан — войти в панель будет нельзя.");
            return;
        }
        AppUser user = users.save(AppUser.create(admin.getUsername(), admin.getDisplayName(),
                encoder.encode(admin.getPassword()), Set.of(Role.ADMIN, Role.STAFF), clock.instant()));
        audit.ok(null, "USER", "system", Map.of("action", "bootstrap", "username", user.getUsername()));
        log.info("Создан начальный администратор {}", user.getUsername());
    }

    @Transactional(readOnly = true)
    public List<AppUser> list() {
        return users.findAllByOrderByUsernameAsc();
    }

    @Transactional(readOnly = true)
    public AppUser require(String username) {
        return users.findByUsernameAndActiveTrue(AppUser.normalizeUsername(username))
                .orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
    }

    @Transactional
    public AppUser create(String username, String displayName, String password, Set<Role> roles, Actor actor) {
        if (username == null || username.isBlank()) {
            throw ApiException.badRequest("username", "Логин обязателен.");
        }
        validatePassword(password, true);
        if (roles == null || roles.isEmpty()) {
            throw ApiException.badRequest("roles", "Укажите хотя бы одну роль.");
        }
        String normalized = AppUser.normalizeUsername(username);
        if (users.findByUsername(normalized).isPresent()) {
            throw ApiException.conflict("username_taken", "Пользователь с таким логином уже есть.");
        }
        AppUser user = users.save(AppUser.create(normalized, displayName, encoder.encode(password), roles, clock.instant()));
        audit.ok(null, "USER", actor.username(), Map.of("action", "create", "username", user.getUsername(), "roles", roles.toString()));
        return user;
    }

    @Transactional
    public AppUser update(UUID id, String displayName, String password, Set<Role> roles, Boolean active, Actor actor) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
        String hash = null;
        if (password != null && !password.isBlank()) {
            validatePassword(password, false);
            hash = encoder.encode(password);
        }
        Set<Role> newRoles = roles == null || roles.isEmpty() ? user.getRoles() : roles;
        if (user.getRoles().contains(Role.ADMIN) && !newRoles.contains(Role.ADMIN)) {
            assertAnotherAdmin(user);
        }
        user.apply(user.getUsername(), displayName == null ? user.getDisplayName() : displayName, hash, newRoles, clock.instant());
        if (active != null && active != user.isActive()) {
            if (!active) {
                if (user.getUsername().equals(AppUser.normalizeUsername(actor.username()))) {
                    throw ApiException.conflict("self", "Нельзя отключить учётную запись текущего сеанса.");
                }
                if (user.getRoles().contains(Role.ADMIN)) {
                    assertAnotherAdmin(user);
                }
            }
            user.setActive(active, clock.instant());
        }
        audit.ok(null, "USER", actor.username(), Map.of("action", "update", "username", user.getUsername(),
                "roles", newRoles.toString(), "active", user.isActive(), "passwordChanged", hash != null));
        return user;
    }

    private void assertAnotherAdmin(AppUser except) {
        boolean another = users.findAllByOrderByUsernameAsc().stream()
                .anyMatch(u -> u.isActive() && !u.getId().equals(except.getId()) && u.getRoles().contains(Role.ADMIN));
        if (!another) {
            throw ApiException.conflict("last_admin", "Должен остаться хотя бы один активный администратор.");
        }
    }

    private static void validatePassword(String password, boolean required) {
        if (password == null || password.isBlank()) {
            if (required) {
                throw ApiException.badRequest("password", "Пароль обязателен.");
            }
            return;
        }
        if (password.length() < MIN_PASSWORD) {
            throw ApiException.badRequest("password", "Пароль не короче " + MIN_PASSWORD + " символов.");
        }
    }
}
