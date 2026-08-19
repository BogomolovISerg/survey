package ru.big.survey.service;

/** Провайдер flash-call: заказывает звонок и возвращает код (последние 4 цифры номера, с которого звонят). */
public interface FlashCallClient {

    /** @return код подтверждения (4 цифры, ведущие нули сохранены) */
    String call(String phone);
}
