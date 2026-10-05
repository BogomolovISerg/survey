package ru.big.survey.persistence;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.big.survey.domain.CallBudget;

public interface CallBudgetRepository extends JpaRepository<CallBudget, LocalDate> {

    /** Атомарно +1 за сутки (создаёт строку при первом звонке дня). */
    @Modifying
    @Query(value = """
            insert into call_budget (budget_day, calls) values (:day, 1)
            on conflict (budget_day) do update set calls = call_budget.calls + 1
            """, nativeQuery = true)
    int increment(@Param("day") LocalDate day);

    @Modifying
    @Query("delete from CallBudget b where b.budgetDay < :before")
    int deleteBefore(@Param("before") LocalDate before);
}
