-- Пустая настройка старых мероприятий сохраняет отображение обеих кнопок.
ALTER TABLE event ADD COLUMN gift_award_mode VARCHAR(20) NOT NULL DEFAULT 'both';
ALTER TABLE event ADD CONSTRAINT event_gift_award_mode_check
    CHECK (gift_award_mode IN ('questionnaire', 'manual', 'both'));
