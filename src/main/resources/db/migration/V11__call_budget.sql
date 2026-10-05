-- Общий суточный счётчик заказанных flash-call (для ограничения расходов, см. survey.verification.max-calls-per-day).
create table call_budget (
    budget_day date    primary key,
    calls      integer not null
);
