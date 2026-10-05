export type GiftAwardMode = "questionnaire" | "manual" | "both";

/** Неизвестное или отсутствующее значение старого сервиса сохраняет обе кнопки. */
export function giftAwardVisibility(event?: { giftEnabled: boolean; giftAwardMode?: GiftAwardMode } | null) {
  return {
    questionnaire: !!event?.giftEnabled && event.giftAwardMode !== "manual",
    manual: !!event?.giftEnabled && event.giftAwardMode !== "questionnaire",
  };
}
