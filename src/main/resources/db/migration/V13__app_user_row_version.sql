-- Версия записи управляется JPA: конкурентная правка отменяется, вместо перезаписи пароля и auth_version.
-- auth_version остаётся отдельным счётчиком отзыва сессий и не меняется при обновлении имени пользователя.
alter table app_user add column row_version bigint not null default 0;
