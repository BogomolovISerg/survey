-- Выдачи без анкеты входят в тот же поток и курсор, что ответы посетителей.
-- Уже выданные подарки получают новые номера, чтобы попасть в выгрузку после обновления.
alter table manual_gift_award add column change_seq bigint;
update manual_gift_award set change_seq = nextval('response_change_seq');
alter table manual_gift_award alter column change_seq set default nextval('response_change_seq');
alter table manual_gift_award alter column change_seq set not null;
create unique index ux_manual_gift_award_seq on manual_gift_award (change_seq);
create index ix_manual_gift_award_event_seq on manual_gift_award (event_id, change_seq);
