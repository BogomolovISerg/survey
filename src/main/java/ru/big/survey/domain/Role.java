package ru.big.survey.domain;

/** Роли локальных пользователей. */
public enum Role {
    /** Персонал стенда: выдача подарков, счётчики. */
    STAFF,
    /** Администратор: всё STAFF + мероприятия, ответы, CSV, журнал, пользователи. */
    ADMIN,
    /** Служебная учётка 1С:ERP для /api/v1/sync (HTTP Basic). */
    INTEGRATION
}
