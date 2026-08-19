package ru.big.survey.service;

import org.springframework.http.HttpStatus;

/** Ошибка бизнес-логики с HTTP-статусом и машинным кодом; сообщение — для пользователя (по-русски). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Object extra;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public Object getExtra() { return extra; }

    public static ApiException notFound(String message) { return new ApiException(HttpStatus.NOT_FOUND, "not_found", message); }
    public static ApiException badRequest(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    public static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    public static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, "forbidden", message); }
    public static ApiException gone(String message) { return new ApiException(HttpStatus.GONE, "closed", message); }
    public static ApiException tooMany(String message, Object extra) { return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many", message, extra); }
    public static ApiException upstream(String message) { return new ApiException(HttpStatus.BAD_GATEWAY, "upstream", message); }
}
