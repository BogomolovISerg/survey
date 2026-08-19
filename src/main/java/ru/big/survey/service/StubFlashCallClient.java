package ru.big.survey.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.big.survey.config.SurveyProperties;

/** Заглушка для теста без реальных звонков: код фиксированный (survey.flash-call.stub-code), пишется в лог. */
@Component
public class StubFlashCallClient implements FlashCallClient {

    private static final Logger log = LoggerFactory.getLogger(StubFlashCallClient.class);

    private final SurveyProperties properties;

    public StubFlashCallClient(SurveyProperties properties) {
        this.properties = properties;
    }

    @Override
    public String call(String phone) {
        String code = properties.getFlashCall().getStubCode();
        log.warn("STUB flash-call для {}: код {} (звонок не выполняется — только для тестового контура!)", Phones.mask(phone), code);
        return code;
    }
}
