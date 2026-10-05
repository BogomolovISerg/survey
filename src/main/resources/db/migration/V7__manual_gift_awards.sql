-- Выдача без анкеты хранится отдельно и не увеличивает число ответов посетителей.
create table manual_gift_award (
    id          uuid primary key,
    event_id    uuid not null references event(id),
    at          timestamptz not null,
    by_user     varchar(128) not null,
    item_codes  jsonb not null default '[]'::jsonb,
    constraint manual_gift_item_codes_array check (jsonb_typeof(item_codes) = 'array')
);
create index ix_manual_gift_award_event_at on manual_gift_award (event_id, at desc);
