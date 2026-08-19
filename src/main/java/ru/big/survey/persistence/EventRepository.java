package ru.big.survey.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.big.survey.domain.Event;

public interface EventRepository extends JpaRepository<Event, UUID> {

    List<Event> findAllByOrderByPublishedAtDesc();

    /** Активные опубликованные мероприятия, идущие сейчас (с запасом по датам) или без дат. */
    @Query("""
            select e from Event e
            where e.active = true and e.currentVersion > 0
              and (e.startsOn is null or e.startsOn <= :until)
              and (e.endsOn is null or e.endsOn >= :since)
            order by e.startsOn desc nulls last, e.name
            """)
    List<Event> findCurrent(@Param("since") LocalDate since, @Param("until") LocalDate until);
}
