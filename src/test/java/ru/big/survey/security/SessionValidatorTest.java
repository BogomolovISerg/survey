package ru.big.survey.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.big.survey.persistence.AppUserRepository;
import ru.big.survey.persistence.UserSessionState;

class SessionValidatorTest {

    private record State(boolean active, int version) implements UserSessionState {
        @Override public boolean isActive() { return active; }
        @Override public int getAuthVersion() { return version; }
    }

    private final Map<String, State> store = new HashMap<>();
    private final SessionValidator validator = new SessionValidator(repository());

    @Test
    void active_user_with_matching_version_is_valid() {
        store.put("ivan", new State(true, 3));

        assertThat(validator.isValid("ivan", 3)).isTrue();
    }

    @Test
    void username_is_matched_case_insensitively_like_at_login() {
        store.put("ivan", new State(true, 3));

        assertThat(validator.isValid("  Ivan ", 3)).isTrue();
    }

    @Test
    void deactivated_user_is_not_valid() {
        store.put("ivan", new State(false, 3));

        assertThat(validator.isValid("ivan", 3)).isFalse();
    }

    @Test
    void changed_version_invalidates_the_old_session() {
        store.put("ivan", new State(true, 4));   // пароль или роли менялись после входа

        assertThat(validator.isValid("ivan", 3)).isFalse();
    }

    @Test
    void deleted_user_or_session_without_stamp_is_not_valid() {
        store.put("ivan", new State(true, 3));

        assertThat(validator.isValid("petr", 3)).isFalse();      // пользователя нет
        assertThat(validator.isValid("ivan", null)).isFalse();   // сессия создана до введения версии
        assertThat(validator.isValid(null, 3)).isFalse();
    }

    private AppUserRepository repository() {
        return (AppUserRepository) Proxy.newProxyInstance(AppUserRepository.class.getClassLoader(),
                new Class<?>[]{AppUserRepository.class}, (self, method, args) -> {
                    if (method.getName().equals("findStateByUsername")) {
                        return Optional.ofNullable(store.get((String) args[0]));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
