package ru.big.survey.service;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import ru.big.survey.config.SurveyProperties;

/** Выбор провайдера по survey.flash-call.provider (zvonok | stub). */
@Component
@Primary
public class FlashCallClientSelector implements FlashCallClient {

    private final FlashCallClient delegate;

    public FlashCallClientSelector(SurveyProperties properties, ZvonokFlashCallClient zvonok, StubFlashCallClient stub) {
        String provider = properties.getFlashCall().getProvider();
        this.delegate = "stub".equalsIgnoreCase(provider) ? stub : zvonok;
    }

    @Override
    public String call(String phone) {
        return delegate.call(phone);
    }
}
