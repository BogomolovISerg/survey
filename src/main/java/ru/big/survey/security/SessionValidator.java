package ru.big.survey.security;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.domain.AppUser;
import ru.big.survey.persistence.AppUserRepository;

/**
 * Проверяет, что сессия персонала всё ещё действительна: пользователь существует, активен, а версия его учётных
 * данных не менялась с момента входа. Так отключение, смена пароля или ролей действуют сразу, а не через 12 часов.
 */
@Service
public class SessionValidator {

    private final AppUserRepository users;

    public SessionValidator(AppUserRepository users) {
        this.users = users;
    }

    /**
     * @param username    имя из сессии
     * @param sessionStamp версия учётных данных, запомненная при входе; null — сессия без отметки (создана до этого
     *                     изменения), её закрываем
     */
    @Transactional(readOnly = true)
    public boolean isValid(String username, Integer sessionStamp) {
        if (username == null || sessionStamp == null) {
            return false;
        }
        return users.findStateByUsername(AppUser.normalizeUsername(username))
                .map(state -> state.isActive() && state.getAuthVersion() == sessionStamp)
                .orElse(false);
    }
}
