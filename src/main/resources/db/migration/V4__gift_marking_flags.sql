-- Флаги мероприятия из 1С: подарки с кодом маркировки / обязательно сканировать КМ при выдаче.
alter table event add column gift_marked        boolean not null default false;
alter table event add column gift_mark_required boolean not null default false;
