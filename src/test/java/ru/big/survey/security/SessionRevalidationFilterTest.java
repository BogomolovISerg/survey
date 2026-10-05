package ru.big.survey.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.big.survey.persistence.AppUserRepository;
import ru.big.survey.persistence.UserSessionState;

/** Закрывает ли фильтр сессию отключённого или изменённого пользователя и пропускает ли остальных. */
class SessionRevalidationFilterTest {

    private record State(boolean active, int version) implements UserSessionState {
        @Override public boolean isActive() { return active; }
        @Override public int getAuthVersion() { return version; }
    }

    private final Map<String, State> users = new HashMap<>();
    private final Map<String, Object> sessionAttributes = new HashMap<>();
    private final AtomicInteger invalidated = new AtomicInteger();
    private final AtomicInteger chained = new AtomicInteger();
    private final SessionRevalidationFilter filter = new SessionRevalidationFilter(new SessionValidator(repository()));

    @Test
    void valid_session_is_kept() throws Exception {
        users.put("ivan", new State(true, 2));
        sessionAttributes.put(SessionRevalidationFilter.VERSION_ATTRIBUTE, 2);

        run(signedIn("ivan"), true);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(invalidated.get()).isEqualTo(0);
        assertThat(chained.get()).isEqualTo(1);
    }

    @Test
    void deactivated_admin_loses_the_session_immediately() throws Exception {
        users.put("admin", new State(false, 2));
        sessionAttributes.put(SessionRevalidationFilter.VERSION_ATTRIBUTE, 2);

        run(signedIn("admin"), true);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(invalidated.get()).isEqualTo(1);
        assertThat(chained.get()).isEqualTo(1);   // запрос идёт дальше как анонимный, 401 вернёт авторизация
    }

    @Test
    void password_change_closes_other_sessions() throws Exception {
        users.put("ivan", new State(true, 3));   // версия выросла после смены пароля
        sessionAttributes.put(SessionRevalidationFilter.VERSION_ATTRIBUTE, 2);

        run(signedIn("ivan"), true);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(invalidated.get()).isEqualTo(1);
    }

    @Test
    void session_without_stamp_is_closed() throws Exception {
        users.put("ivan", new State(true, 2));

        run(signedIn("ivan"), true);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(invalidated.get()).isEqualTo(1);
    }

    @Test
    void anonymous_requests_are_not_checked() throws Exception {
        run(null, false);
        run(new AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")), false);

        assertThat(invalidated.get()).isEqualTo(0);
        assertThat(chained.get()).isEqualTo(2);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------- помощники ----------

    private UsernamePasswordAuthenticationToken signedIn(String username) {
        return UsernamePasswordAuthenticationToken.authenticated(username, null, AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
    }

    private void run(org.springframework.security.core.Authentication authentication, boolean hasSession) throws Exception {
        SecurityContextHolder.clearContext();
        if (authentication != null) {
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        HttpSession session = (HttpSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HttpSession.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getAttribute" -> sessionAttributes.get((String) args[0]);
                    case "invalidate" -> {
                        invalidated.incrementAndGet();
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (self, method, args) -> {
                    if (method.getName().equals("getSession")) {
                        return hasSession ? session : null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        FilterChain chain = (ServletRequest req, ServletResponse res) -> chained.incrementAndGet();
        filter.doFilter(request, null, chain);
    }

    private AppUserRepository repository() {
        return (AppUserRepository) Proxy.newProxyInstance(AppUserRepository.class.getClassLoader(),
                new Class<?>[]{AppUserRepository.class}, (self, method, args) -> {
                    if (method.getName().equals("findStateByUsername")) {
                        return Optional.ofNullable(users.get((String) args[0]));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
