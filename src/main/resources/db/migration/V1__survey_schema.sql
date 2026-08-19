-- Анкетирование посетителей выставок: схема БД (PostgreSQL 14+).
-- Идентификатор мероприятия = GUID справочника бигМероприятия в 1С:ERP.

create table event (
    id              uuid primary key,
    name            varchar(512) not null,
    starts_on       date,
    ends_on         date,
    gift_enabled    boolean not null default false,
    active          boolean not null default true,
    theme           jsonb,
    current_version integer not null default 0,
    published_at    timestamptz not null,
    updated_at      timestamptz not null
);

create table questionnaire (
    id              uuid primary key,
    event_id        uuid not null references event(id),
    version         integer not null,
    schema          jsonb not null,
    checksum        varchar(64) not null,
    published_at    timestamptz not null,
    unique (event_id, version)
);

-- Сквозной счётчик изменений ответов: растёт при создании ответа и при любом изменении подарка.
-- 1С забирает ответы с change_seq > acked_seq и подтверждает курсор.
create sequence response_change_seq;

create table response (
    id                    uuid primary key,
    event_id              uuid not null references event(id),
    questionnaire_version integer not null,
    phone                 varchar(20) not null,          -- цифры без "+", 7XXXXXXXXXX
    answers               jsonb not null,                -- { "Имя": "…", "Город": "…", … } как в v1
    submitted_at          timestamptz not null,
    consent_at            timestamptz,
    gift_awarded          boolean not null default false,
    gift_awarded_at       timestamptz,
    gift_awarded_by       varchar(256),
    gift_code             varchar(8) not null,           -- короткий код для ручного ввода на стенде
    change_seq            bigint not null,
    client                jsonb,                         -- user-agent, ip (для разбора инцидентов)
    unique (event_id, phone),
    unique (event_id, gift_code)
);
create index ix_response_event_seq on response (event_id, change_seq);
create index ix_response_event_submitted on response (event_id, submitted_at desc);

create table gift_award (
    id          uuid primary key,
    response_id uuid not null references response(id),
    awarded     boolean not null,
    at          timestamptz not null,
    by_user     varchar(256) not null,
    source      varchar(16) not null                     -- scan | code | admin | visitor
);
create index ix_gift_award_response on gift_award (response_id, at);

create table phone_verification (
    phone         varchar(20) primary key,
    code_hash     varchar(64),
    attempts      integer not null default 0,
    verified      boolean not null default false,
    created_at    timestamptz not null,
    last_call_at  timestamptz,
    verified_at   timestamptz,
    expires_at    timestamptz,
    calls_day     date,
    calls_today   integer not null default 0
);
create index ix_phone_verification_expires on phone_verification (expires_at);

create table app_user (
    id            uuid primary key,
    username      varchar(128) not null unique,
    display_name  varchar(256) not null,
    password_hash varchar(256) not null,
    active        boolean not null default true,
    created_at    timestamptz not null,
    updated_at    timestamptz not null
);

create table app_user_role (
    user_id uuid not null references app_user(id) on delete cascade,
    role    varchar(32) not null check (role in ('ADMIN', 'STAFF', 'INTEGRATION')),
    primary key (user_id, role)
);

create table sync_state (
    event_id        uuid primary key references event(id),
    acked_seq       bigint not null default 0,
    last_export_at  timestamptz,
    last_ack_at     timestamptz,
    last_publish_at timestamptz
);

create table sync_log (
    id        uuid primary key,
    event_id  uuid,
    kind      varchar(16) not null,   -- PUBLISH | EXPORT | ACK | LOGIN | USER | GIFT | CSV
    at        timestamptz not null,
    actor     varchar(256) not null,
    status    varchar(16) not null,   -- OK | ERROR
    details   jsonb
);
create index ix_sync_log_at on sync_log (at desc);
create index ix_sync_log_event on sync_log (event_id, at desc);
