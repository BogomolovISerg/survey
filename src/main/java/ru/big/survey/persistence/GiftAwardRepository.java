package ru.big.survey.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.big.survey.domain.GiftAward;

public interface GiftAwardRepository extends JpaRepository<GiftAward, UUID> {
    List<GiftAward> findAllByResponseIdOrderByAtAsc(UUID responseId);
}
