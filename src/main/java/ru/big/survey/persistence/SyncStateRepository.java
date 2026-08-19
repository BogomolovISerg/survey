package ru.big.survey.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.big.survey.domain.SyncState;

public interface SyncStateRepository extends JpaRepository<SyncState, UUID> {
}
