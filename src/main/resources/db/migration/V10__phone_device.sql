-- Браузеры (устройства), на которых телефон уже подтверждён кодом flash-call.
-- В cookie у посетителя лежит случайный секрет, здесь — только его SHA-256 (hex).
-- Повторный заход без звонка разрешается только для пары (телефон, устройство).
create table phone_device (
    phone       varchar(20) not null,
    device_hash varchar(64) not null,
    created_at  timestamptz not null,
    expires_at  timestamptz not null,
    primary key (phone, device_hash)
);
create index ix_phone_device_expires on phone_device (expires_at);
