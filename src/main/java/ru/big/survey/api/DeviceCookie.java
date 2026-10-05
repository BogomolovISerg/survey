package ru.big.survey.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Cookie устройства посетителя (SURVEYDEV): случайный секрет, по которому сервер узнаёт браузер, где телефон уже
 * подтверждали кодом. Путь ограничен публичным API анкеты; JS значение не видит (HttpOnly).
 */
final class DeviceCookie {

    static final String NAME = "SURVEYDEV";

    private DeviceCookie() {
    }

    /** Значение cookie или null. Проверка формата — в {@code DeviceSecrets.isWellFormed}. */
    static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    static void write(HttpServletRequest request, HttpServletResponse response, String secret, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(NAME, secret)
                .httpOnly(true)
                .secure(request.isSecure())   // за nginx со схемой https — через forward-headers (X-Forwarded-Proto)
                .sameSite("Lax")
                .path(request.getContextPath() + "/api/v1/public")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
