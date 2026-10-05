-- Режим сотрудника при коде маркировки «не в обороте» (проверка в «Честном знаке»):
-- true — выдача блокируется, false — только предупреждение на стенде.
alter table app_user add column block_rejected_marks boolean not null default true;
