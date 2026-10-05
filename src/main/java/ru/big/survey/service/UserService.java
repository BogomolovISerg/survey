package ru.big.survey.service;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.config.SurveyProperties;
import ru.big.survey.domain.AppUser;
import ru.big.survey.domain.Role;
import ru.big.survey.persistence.AppUserRepository;
import ru.big.survey.persistence.EventRepository;
import ru.big.survey.security.Actor;

/**
 * Локальный реестр пользователей: STAFF / SUPERVISOR / ADMIN / INTEGRATION.
 * Супервайзер управляет только сотрудниками стенда (STAFF) в рамках назначенных ему мероприятий.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int MIN_PASSWORD = 8;

    private final AppUserRepository users;
    private final EventRepository events;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final Clock clock;

    public UserService(AppUserRepository users, EventRepository events, PasswordEncoder encoder, AuditService audit, Clock clock) {
        this.users = users;
        this.events = events;
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

    /** ADMIN видит всех; SUPERVISOR — только STAFF-пользователей, пересекающихся с его мероприятиями. */
    @Transactional(readOnly = true)
    public List<AppUser> list(Actor actor) {
        List<AppUser> all = users.findAllByOrderByUsernameAsc();
        if (!actor.isSupervisor()) {
            return all;
        }
        Set<UUID> allowed = allowedEventIds(actor);
        return all.stream().filter(u -> isManageableStaff(u, allowed)).toList();
    }

    @Transactional(readOnly = true)
    public AppUser require(String username) {
        return users.findByUsernameAndActiveTrue(AppUser.normalizeUsername(username))
                .orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
    }

    /** Проверяем, что пароль и роли, использованные при входе, ещё действуют до создания сессии. */
    @Transactional(readOnly = true)
    public AppUser requireAuthenticatedUser(String username, int authVersion) {
        return users.findByUsernameAndActiveTrue(AppUser.normalizeUsername(username))
                .filter(user -> user.getAuthVersion() == authVersion)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "credentials_changed",
                        "Учётные данные изменились. Повторите вход."));
    }

    /**
     * Мероприятия, доступные пользователю: null — без ограничений (ADMIN),
     * иначе набор назначенных (пустой = ничего не назначено). Для STAFF и SUPERVISOR.
     */
    @Transactional(readOnly = true)
    public Set<java.util.UUID> allowedEventIds(Actor actor) {
        if (actor.isAdmin()) {
            return null;
        }
        return users.findByUsernameAndActiveTrue(AppUser.normalizeUsername(actor.username()))
                .map(AppUser::getEventIds)
                .orElse(Set.of());
    }

    @Transactional
    public AppUser create(String username, String displayName, String password, Set<Role> roles, Set<java.util.UUID> eventIds, Actor actor) {
        if (username == null || username.isBlank()) {
            throw ApiException.badRequest("username", "Логин обязателен.");
        }
        validatePassword(password, true);
        if (actor.isSupervisor()) {
            roles = Set.of(Role.STAFF);
            eventIds = supervisorScopedEvents(actor, eventIds, Set.of());
            if (eventIds.isEmpty()) {
                throw ApiException.badRequest("event", "Назначьте сотрудника хотя бы на одно из ваших мероприятий.");
            }
        }
        if (roles == null || roles.isEmpty()) {
            throw ApiException.badRequest("roles", "Укажите хотя бы одну роль.");
        }
        String normalized = AppUser.normalizeUsername(username);
        if (users.findByUsername(normalized).isPresent()) {
            throw ApiException.conflict("username_taken", "Пользователь с таким логином уже есть.");
        }
        AppUser user = AppUser.create(normalized, displayName, encoder.encode(password), roles, clock.instant());
        user.setEvents(validatedEvents(eventIds), clock.instant());
        user = users.save(user);
        audit.ok(null, "USER", actor.username(), Map.of("action", "create", "username", user.getUsername(),
                "roles", roles.toString(), "events", user.getEventIds().size()));
        return user;
    }

    @Transactional
    public AppUser update(UUID id, String displayName, String password, Set<Role> roles, Boolean active,
                          Boolean blockRejectedMarks, Set<UUID> eventIds, Actor actor) {
        AppUser user = users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь не найден"));
        if (actor.isSupervisor()) {
            if (!isManageableStaff(user, allowedEventIds(actor))) {
                throw ApiException.forbidden("Этот пользователь вам не подчинён.");
            }
            roles = null; // роли меняет только администратор
            if (eventIds != null) {
                eventIds = supervisorScopedEvents(actor, eventIds, user.getEventIds());
            }
        }
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
        if (eventIds != null) {
            user.setEvents(validatedEvents(eventIds), clock.instant());
        }
        if (blockRejectedMarks != null) {
            user.setBlockRejectedMarks(blockRejectedMarks, clock.instant());
        }
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
        audit.okInTransaction(null, "USER", actor.username(), Map.of("action", "update", "username", user.getUsername(),
                "roles", newRoles.toString(), "active", user.isActive(), "passwordChanged", hash != null,
                "events", user.getEventIds().size()));
        return user;
    }

    /** Пользователь, которым может управлять супервайзер: только роль STAFF и пересечение мероприятий. */
    private static boolean isManageableStaff(AppUser user, Set<UUID> allowed) {
        if (allowed == null || !user.getRoles().equals(Set.of(Role.STAFF))) {
            return false;
        }
        return user.getEventIds().stream().anyMatch(allowed::contains);
    }

    /**
     * Назначения при правке супервайзером: в рамках его мероприятий — как запрошено,
     * назначения на чужие мероприятия сохраняются без изменений.
     */
    private Set<UUID> supervisorScopedEvents(Actor actor, Set<UUID> requested, Set<UUID> current) {
        Set<UUID> allowed = allowedEventIds(actor);
        Set<UUID> result = new HashSet<>();
        for (UUID id : current) {
            if (!allowed.contains(id)) {
                result.add(id); // чужое назначение — не трогаем
            }
        }
        if (requested != null) {
            for (UUID id : requested) {
                if (!allowed.contains(id)) {
                    if (!current.contains(id)) {
                        throw ApiException.forbidden("Мероприятие не входит в ваши назначения.");
                    }
                    continue; // уже учтено выше
                }
                result.add(id);
            }
        }
        return result;
    }

    private Set<UUID> validatedEvents(Set<UUID> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return Set.of();
        }
        for (UUID id : eventIds) {
            if (!events.existsById(id)) {
                throw ApiException.badRequest("event", "Мероприятие " + id + " не найдено в сервисе.");
            }
        }
        return eventIds;
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
