package ru.big.survey.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Перед авторизацией запроса сверяет вошедшего пользователя с БД ({@link SessionValidator}). Если пользователь отключён
 * или его пароль/роли менялись после входа, сессия закрывается, и запрос дальше идёт как анонимный (401 на закрытых путях).
 */
public class SessionRevalidationFilter implements Filter {

    /** Атрибут сессии: версия учётных данных пользователя на момент входа. */
    public static final String VERSION_ATTRIBUTE = "survey.authVersion";

    private final SessionValidator validator;

    public SessionRevalidationFilter(SessionValidator validator) {
        this.validator = validator;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && request instanceof HttpServletRequest http) {
            HttpSession session = http.getSession(false);
            Object stamp = session == null ? null : session.getAttribute(VERSION_ATTRIBUTE);
            if (!validator.isValid(authentication.getName(), stamp instanceof Integer version ? version : null)) {
                SecurityContextHolder.clearContext();
                if (session != null) {
                    session.invalidate();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
