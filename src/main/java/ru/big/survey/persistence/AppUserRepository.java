package ru.big.survey.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.big.survey.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByUsernameAndActiveTrue(String username);

    List<AppUser> findAllByOrderByUsernameAsc();

    long countByActiveTrue();
}
