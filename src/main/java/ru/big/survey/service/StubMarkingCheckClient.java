package ru.big.survey.service;

import org.springframework.stereotype.Component;

/**
 * Заглушка проверки «Честного знака» для теста (survey.marking-check.provider=stub):
 * коды, содержащие "BAD" — отклоняются, содержащие "ERR" — «сервис недоступен», остальные — в обороте.
 */
@Component
public class StubMarkingCheckClient implements MarkingCheckClient {

    @Override
    public Verdict check(String code) {
        if (code.contains("BAD")) {
            return Verdict.rejected("Код не в обороте (заглушка).");
        }
        if (code.contains("ERR")) {
            return Verdict.unknown("Сервис «Честный знак» недоступен (заглушка).");
        }
        return Verdict.ok();
    }
}
