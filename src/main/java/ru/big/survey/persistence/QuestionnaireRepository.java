package ru.big.survey.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.big.survey.domain.Questionnaire;

public interface QuestionnaireRepository extends JpaRepository<Questionnaire, UUID> {

    Optional<Questionnaire> findByEventIdAndVersion(UUID eventId, int version);

    Optional<Questionnaire> findFirstByEventIdOrderByVersionDesc(UUID eventId);

    List<Questionnaire> findAllByEventIdOrderByVersionDesc(UUID eventId);
}
