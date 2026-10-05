import { useEffect, useState } from "react";
import { Link } from "react-router";
import { adminApi, ApiError, type EventSummary } from "../api/client";
import { fmtDate, fmtDateTime } from "../lib/format";
import { Alert } from "../ui/Alert";

export function AdminEvents() {
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [error, setError] = useState("");

  const load = () =>
    adminApi
      .events()
      .then(setEvents)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  useEffect(() => {
    void load();
  }, []);

  const toggle = async (e: EventSummary) => {
    if (!confirm(e.active ? `Закрыть анкету «${e.name}»? Посетители увидят «мероприятие завершено».` : `Открыть анкету «${e.name}»?`)) return;
    try {
      await adminApi.setActive(e.id, !e.active);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось изменить");
    }
  };

  return (
    <div className="page page-wide">
      <h1>Мероприятия</h1>
      {error && <Alert kind="error">{error}</Alert>}
      {events && events.length === 0 && <Alert kind="info">Пока ничего не опубликовано. Публикация выполняется из 1С (кнопка «Опубликовать» в карточке мероприятия).</Alert>}
      {events && events.length > 0 && (
        <div className="card table-wrap">
          <table>
            <thead>
              <tr>
                <th>Мероприятие</th><th>Даты</th><th>Версия</th><th>Анкет</th><th>Подарков</th><th>Не выгружено в 1С</th><th>Статус</th><th></th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.id}>
                  <td><Link to={`/admin/events/${e.id}`}>{e.name}</Link><div className="muted small">{e.id}</div></td>
                  <td className="small">{fmtDate(e.startsOn)}{e.endsOn ? ` — ${fmtDate(e.endsOn)}` : ""}</td>
                  <td>{e.version}<div className="muted small">{fmtDateTime(e.publishedAt)}</div></td>
                  <td>{e.responses}</td>
                  <td>{e.giftEnabled ? e.giftsAwarded : "—"}</td>
                  <td>{e.pending}{e.lastAckAt ? <div className="muted small">ack {fmtDateTime(e.lastAckAt)}</div> : null}</td>
                  <td><span className={`badge ${e.active ? "ok" : "muted"}`}>{e.active ? "открыта" : "закрыта"}</span></td>
                  <td><button type="button" className="sm secondary" onClick={() => toggle(e)}>{e.active ? "Закрыть" : "Открыть"}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
