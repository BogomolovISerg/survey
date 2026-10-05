-- Версия учётных данных пользователя: растёт при смене пароля, ролей и признака «активен».
-- Сессия запоминает версию при входе; если она не совпала с БД, сессия закрывается (см. SessionRevalidationFilter).
alter table app_user add column auth_version integer not null default 0;
