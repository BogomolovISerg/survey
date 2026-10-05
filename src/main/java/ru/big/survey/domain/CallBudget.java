package ru.big.survey.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** Сколько flash-call заказано за сутки по всему сервису. Увеличивается только через {@code CallBudgetRepository#increment}. */
@Entity
@Table(name = "call_budget")
public class CallBudget {

    @Id
    @Column(name = "budget_day")
    private LocalDate budgetDay;

    @Column(nullable = false)
    private int calls;

    protected CallBudget() {
    }

    public CallBudget(LocalDate budgetDay, int calls) {
        this.budgetDay = budgetDay;
        this.calls = calls;
    }

    public LocalDate getBudgetDay() { return budgetDay; }
    public int getCalls() { return calls; }
}
