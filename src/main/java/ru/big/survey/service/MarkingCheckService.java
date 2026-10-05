package ru.big.survey.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import ru.big.survey.config.SurveyProperties;

/**
 * Проверка кодов маркировки в «Честном знаке» с кэшем вердиктов (survey.marking-check.*).
 * Выбор провайдера: crpt (публичное API) | stub (тест) | off (проверка выключена).
 * UNKNOWN (ЧЗ недоступен) кэшу не подлежит — следующий скан попробует ещё раз.
 */
@Service
public class MarkingCheckService {

    private record Cached(MarkingCheckClient.Verdict verdict, Instant at) {}

    private final SurveyProperties properties;
    private final MarkingCheckClient delegate;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public MarkingCheckService(SurveyProperties properties, CrptMarkingCheckClient crpt,
                               StubMarkingCheckClient stub, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        String provider = properties.getMarkingCheck().getProvider();
        this.delegate = "stub".equalsIgnoreCase(provider) ? stub : crpt;
    }

    /** Проверка включена (provider не off). */
    public boolean enabled() {
        return !"off".equalsIgnoreCase(properties.getMarkingCheck().getProvider());
    }

    /** При недоступности ЧЗ выдача не блокируется. */
    public boolean failOpen() {
        return properties.getMarkingCheck().isFailOpen();
    }

    public MarkingCheckClient.Verdict check(String code) {
        if (!enabled()) {
            return MarkingCheckClient.Verdict.ok();
        }
        Instant now = clock.instant();
        Cached cached = cache.get(code);
        if (cached != null && cached.at().plus(properties.getMarkingCheck().getCacheTtl()).isAfter(now)) {
            return cached.verdict();
        }
        MarkingCheckClient.Verdict verdict = delegate.check(code);
        if (verdict.status() != MarkingCheckClient.Status.UNKNOWN) {
            cache.put(code, new Cached(verdict, now));
            if (cache.size() > 10_000) {
                cache.clear(); // простая защита от разрастания за многодневную выставку
            }
        }
        return verdict;
    }
}
