package ru.big.survey.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Версия учётных данных из того же снимка БД, что проверяемый пароль и роли пользователя. */
public final class AppUserPrincipal extends User {
    private static final long serialVersionUID = 1L;
    private final int authVersion;

    public AppUserPrincipal(String username, String password, Collection<? extends GrantedAuthority> authorities,
                            int authVersion) {
        super(username, password, authorities);
        this.authVersion = authVersion;
    }

    public int getAuthVersion() { return authVersion; }
}
