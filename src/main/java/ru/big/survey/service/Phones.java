package ru.big.survey.service;

/** Нормализация телефона как в v1: только цифры, 8XXXXXXXXXX → 7XXXXXXXXXX. */
public final class Phones {

    private Phones() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        String d = digits.toString();
        if (d.length() == 10) {
            d = "7" + d;
        }
        if (d.length() == 11 && d.charAt(0) == '8') {
            d = "7" + d.substring(1);
        }
        return d;
    }

    public static boolean isValid(String normalized) {
        return normalized != null && normalized.length() == 11 && normalized.charAt(0) == '7';
    }

    /** +7 9** *** ** 45 — для журналов и интерфейса персонала. */
    public static String mask(String normalized) {
        if (normalized == null || normalized.length() < 4) {
            return "***";
        }
        return "+" + normalized.charAt(0) + " " + normalized.charAt(1) + "** *** ** " + normalized.substring(normalized.length() - 2);
    }
}
