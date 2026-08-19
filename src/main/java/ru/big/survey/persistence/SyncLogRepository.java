package ru.big.survey.persistence;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.big.survey.domain.SyncLog;

public interface SyncLogRepository extends JpaRepository<SyncLog, UUID> {

    Page<SyncLog> findAllByOrderByAtDesc(Pageable pageable);

    Page<SyncLog> findAllByEventIdOrderByAtDesc(UUID eventId, Pageable pageable);
}
