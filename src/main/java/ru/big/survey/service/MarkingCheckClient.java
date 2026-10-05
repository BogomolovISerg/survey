package ru.big.survey.service;

/** Онлайн-проверка кода маркировки в «Честном знаке». */
public interface MarkingCheckClient {

    enum Status {
        /** Код найден и находится в обороте — выдача разрешена. */
        IN_CIRCULATION,
        /** Код не найден / не введён в оборот / уже выведен — выдача блокируется. */
        REJECTED,
        /** Проверить не удалось (ЧЗ недоступен или ответ не разобран) — решает fail-open. */
        UNKNOWN
    }

    record Verdict(Status status, String detail) {
        public static Verdict ok() {
            return new Verdict(Status.IN_CIRCULATION, "");
        }

        public static Verdict rejected(String detail) {
            return new Verdict(Status.REJECTED, detail);
        }

        public static Verdict unknown(String detail) {
            return new Verdict(Status.UNKNOWN, detail);
        }
    }

    Verdict check(String code);
}
