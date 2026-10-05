package ru.big.survey.persistence;

/** Минимум о пользователе для проверки живой сессии: без загрузки ролей и назначений. */
public interface UserSessionState {

    boolean isActive();

    int getAuthVersion();
}
