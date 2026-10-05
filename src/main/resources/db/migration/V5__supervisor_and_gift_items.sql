-- Роль SUPERVISOR (локальный админ мероприятия) — расширяем ограничение ролей
alter table app_user_role drop constraint app_user_role_role_check;
alter table app_user_role add constraint app_user_role_role_check
    check (role in ('ADMIN', 'SUPERVISOR', 'STAFF', 'INTEGRATION'));

-- Коды маркировки выданных подарков: несколько КМ на один подарок, история сохраняется
-- при спорном снятии отметки администратором (active остаётся true — подарок фактически выдавался).
create table gift_item (
    id          uuid primary key,
    response_id uuid not null references response(id) on delete cascade,
    code        varchar(512) not null,
    active      boolean not null default true,
    added_at    timestamptz not null,
    added_by    varchar(128) not null,
    unique (response_id, code)
);
create index gift_item_response_idx on gift_item(response_id);

-- Существующие одиночные КМ переносим в новую таблицу
insert into gift_item (id, response_id, code, active, added_at, added_by)
select gen_random_uuid(), r.id, r.gift_item_code, true,
       coalesce(r.gift_awarded_at, r.submitted_at), coalesce(r.gift_awarded_by, 'migration')
from response r
where r.gift_item_code is not null and r.gift_item_code <> '';
