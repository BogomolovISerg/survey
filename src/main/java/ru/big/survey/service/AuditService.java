package ru.big.survey.service;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.big.survey.domain.SyncLog;
import ru.big.survey.persistence.SyncLogRepository;

/** Журнал обмена и действий (таблица sync_log). Пишется в отдельной транзакции, чтобы запись об ошибке не откатывалась. */
@Service
public class AuditService {

    private final SyncLogRepository logs;
    private final Json json;
    private final Clock clock;

    public AuditService(SyncLogRepository logs, Json json, Clock clock) {
        this.logs = logs;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ok(UUID eventId, String kind, String actor, Map<String, ?> details) {
        logs.save(SyncLog.of(eventId, kind, actor, "OK", json.write(details), clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void error(UUID eventId, String kind, String actor, Map<String, ?> details) {
        logs.save(SyncLog.of(eventId, kind, actor, "ERROR", json.write(details), clock.instant()));
    }
}
