package ru.big.survey.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Версия учётных данных растёт ровно тогда, когда старые сессии должны закрываться. */
class AppUserAuthVersionTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    private AppUser staff() {
        return AppUser.create("ivan", "Иван", "hash-1", Set.of(Role.STAFF), NOW);
    }

    @Test
    void password_change_bumps_the_version() {
        AppUser user = staff();
        int before = user.getAuthVersion();

        user.apply(user.getUsername(), user.getDisplayName(), "hash-2", user.getRoles(), NOW);

        assertThat(user.getAuthVersion()).isEqualTo(before + 1);
    }

    @Test
    void role_change_bumps_the_version() {
        AppUser user = staff();
        int before = user.getAuthVersion();

        user.apply(user.getUsername(), user.getDisplayName(), null, Set.of(Role.STAFF, Role.ADMIN), NOW);

        assertThat(user.getAuthVersion()).isEqualTo(before + 1);
    }

    @Test
    void harmless_edits_keep_the_version() {
        AppUser user = staff();
        int before = user.getAuthVersion();

        user.apply(user.getUsername(), "Иван Петров", null, Set.of(Role.STAFF), NOW);   // имя, тот же состав ролей
        user.setEvents(Set.of(UUID.randomUUID()), NOW);
        user.setBlockRejectedMarks(false, NOW);
        user.setActive(true, NOW);                                                      // уже активен

        assertThat(user.getAuthVersion()).isEqualTo(before);
    }

    @Test
    void deactivation_and_reactivation_both_bump_so_old_sessions_do_not_revive() {
        AppUser user = staff();
        int before = user.getAuthVersion();

        user.setActive(false, NOW);
        int afterOff = user.getAuthVersion();
        user.setActive(true, NOW);

        assertThat(afterOff).isEqualTo(before + 1);
        assertThat(user.getAuthVersion()).isEqualTo(before + 2);
    }
}
