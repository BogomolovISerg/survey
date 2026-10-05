import { useEffect, useState } from "react";
import { Link } from "react-router";
import { ApiError, staffApi, type EventSummary, type Stats } from "../api/client";
import { fmtTime } from "../lib/format";
import { Alert } from "../ui/Alert";

const STORAGE = "survey.staff.event";

/** Главная панели стенда: выбор мероприятия и живые счётчики (опрос каждые 5 с). */
export function StaffHome() {
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [eventId, setEventId] = useState<string>(() => localStorage.getItem(STORAGE) ?? "");
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    staffApi
      .events()
      .then((list) => {
        setEvents(list);
        if (list.length > 0 && !list.some((e) => e.id === eventId)) setEventId(list[0].id);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Не удалось загрузить мероприятия."));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!eventId) return;
    localStorage.setItem(STORAGE, eventId);
    let alive = true;
    const tick = () =>
      staffApi
        .stats(eventId)
        .then((s) => alive && setStats(s))
        .catch(() => undefined);
    void tick();
    const id = window.setInterval(tick, 5000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, [eventId]);

  return (
    <div className="page">
      {error && <Alert kind="error">{error}</Alert>}
      {events && events.length === 0 && <Alert kind="info">Сейчас нет активных мероприятий. Опубликуйте мероприятие из 1С.</Alert>}
      {events && events.length > 0 && (
        <div className="card">
          <label className="lbl" htmlFor="ev">Мероприятие</label>
          <select id="ev" value={eventId} onChange={(e) => setEventId(e.target.value)}>
            {events.map((e) => (
              <option key={e.id} value={e.id}>{e.name}</option>
            ))}
          </select>
          <div className="row mt">
            <Link className="btn block" to={`/staff/gift?event=${eventId}`}>Выдать подарок</Link>
          </div>
        </div>
      )}
      {stats && (
        <>
          <div className="stats">
            <div className="stat"><b>{stats.total}</b><span>анкет всего</span></div>
            <div className="stat"><b>{stats.today}</b><span>сегодня</span></div>
            <div className="stat"><b>{stats.lastHour}</b><span>за последний час</span></div>
            {stats.giftEnabled && <div className="stat"><b>{stats.giftsAwarded}</b><span>подарков выдано</span></div>}
          </div>
          <div className="card mt">
            <h3>Последние анкеты</h3>
            {stats.recent.length === 0 && <p className="muted small">Пока пусто.</p>}
            {stats.recent.map((r) => (
              <div className="list-item" key={r.responseId}>
                <div>
                  <div>{r.visitor}</div>
                  <div className="muted small">{r.city}</div>
                </div>
                <div className="center">
                  <div className="small">{fmtTime(r.submittedAt)}</div>
                  {stats.giftEnabled && <span className={`badge ${r.awarded ? "ok" : "muted"}`}>{r.awarded ? "подарок выдан" : "без подарка"}</span>}
                </div>
              </div>
            ))}
            <p className="muted small mt" style={{ marginBottom: 0 }}>Обновлено {fmtTime(stats.at)}</p>
          </div>
        </>
      )}
    </div>
  );
}
