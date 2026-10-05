-- Привязка персонала стенда к мероприятиям: админ назначает, STAFF видит и обслуживает только свои мероприятия.
create table app_user_event (
    user_id  uuid not null references app_user(id) on delete cascade,
    event_id uuid not null references event(id),
    primary key (user_id, event_id)
);
