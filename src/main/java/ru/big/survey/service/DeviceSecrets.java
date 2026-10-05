package ru.big.survey.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Секрет устройства посетителя: 32 случайных байта в base64url (43 символа). Хранится в cookie браузера,
 * в БД попадает только SHA-256 (hex), поэтому утечка таблицы не позволяет подделать cookie.
 */
public final class DeviceSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9_-]{43}");

    private DeviceSecrets() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Значение cookie, пришедшее от клиента, ему не доверяем: годится только строка ожидаемого вида. */
    public static boolean isWellFormed(String secret) {
        return secret != null && WELL_FORMED.matcher(secret).matches();
    }

    public static String hash(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
