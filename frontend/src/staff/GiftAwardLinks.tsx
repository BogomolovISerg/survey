import { NavLink } from "react-router";
import type { EventSummary } from "../api/client";
import { giftAwardVisibility } from "../lib/giftAwardMode";

/** Одинаковые варианты выдачи на стенде и в верхнем меню. */
export function GiftAwardLinks({ event, navigation = false }: { event?: EventSummary | null; navigation?: boolean }) {
  const visible = giftAwardVisibility(event);
  const query = event ? `?event=${encodeURIComponent(event.id)}` : "";
  return <>
    {visible.questionnaire && <NavLink className={navigation ? undefined : "btn grow"} to={`/staff/gift${query}`} end>Выдача с анкетой</NavLink>}
    {visible.manual && <NavLink className={navigation ? undefined : "btn secondary grow"} to={`/staff/gift/manual${query}`}>Выдача без анкеты</NavLink>}
  </>;
}
