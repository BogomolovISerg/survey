import { useEffect, useState } from "react";
import { staffApi, type Stats } from "../api/client";
import { fmtDateTime, fmtTime } from "../lib/format";
import { Alert } from "../ui/Alert";
import { QrCode } from "../ui/QrCode";

import { usePanel } from "./StaffLayout";
import { GiftAwardLinks } from "./GiftAwardLinks";

/** Главная панели стенда: выбор мероприятия и живые счётчики (опрос каждые 5 с). */
export function StaffHome() {
  const { events, eventId, setEventId, eventError: error } = usePanel();
  const [stats, setStats] = useState<Stats | null>(null);
  /** показать посетителю QR-код анкеты на экране телефона сотрудника */
  const [showQr, setShowQr] = useState(false);

  const current = events?.find((e) => e.id === eventId) ?? null;

  useEffect(() => {
    setStats(null);
    setShowQr(false);
    if (!eventId) return;
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
      {events && events.length === 0 && (
        <Alert kind="info">Вам пока не назначено ни одного мероприятия. Обратитесь к администратору.</Alert>
      )}
      {events && events.length === 1 && (
        <div className="card">
          <div className="muted small">Ваше мероприятие</div>
          <h2 style={{ marginBottom: 4 }}>{events[0].name}</h2>
          <div className="row mt">
            <GiftAwardLinks event={current} />
            <button type="button" className="secondary grow" onClick={() => setShowQr(true)}>QR-код анкеты</button>
          </div>
        </div>
      )}
      {events && events.length > 1 && (
        <div className="card">
          <label className="lbl" htmlFor="ev">Мероприятие</label>
          <select id="ev" value={eventId} onChange={(e) => setEventId(e.target.value)}>
            {events.map((e) => (
              <option key={e.id} value={e.id}>{e.name}</option>
            ))}
          </select>
          <div className="row mt">
            <GiftAwardLinks event={current} />
            <button type="button" className="secondary grow" onClick={() => setShowQr(true)}>QR-код анкеты</button>
          </div>
        </div>
      )}
      {showQr && current && (
        <div className="qr-overlay" role="dialog" aria-label="QR-код анкеты" onClick={() => setShowQr(false)}>
          <div className="qr-sheet" onClick={(e) => e.stopPropagation()}>
            <h2 className="center">{current.name}</h2>
            <p className="muted small center">Отсканируйте QR-код камерой телефона, чтобы открыть анкету</p>
            <QrCode value={current.publicUrl} label="QR-код анкеты" />
            <p className="muted small center" style={{ wordBreak: "break-all" }}>{current.publicUrl}</p>
            <button type="button" className="secondary block" onClick={() => setShowQr(false)}>Закрыть</button>
          </div>
        </div>
      )}

      {stats && (
        <>
          <div className="stats">
            <div className="stat"><b>{stats.total}</b><span>анкет всего</span></div>
            <div className="stat"><b>{stats.today}</b><span>сегодня</span></div>
            <div className="stat"><b>{stats.lastHour}</b><span>за последний час</span></div>
            {stats.giftEnabled && <div className="stat"><b>{stats.giftsAwarded}</b><span>подарков выдано</span><small className="muted" style={{ display: "block", marginTop: 4 }}>без анкеты: {stats.manualGiftsAwarded}</small></div>}
          </div>
          {stats.recentManualGifts.length > 0 && (
            <div className="card mt">
              <h3>Последние выдачи без анкеты</h3>
              {stats.recentManualGifts.map((g) => (
                <div className="list-item" key={g.awardId}>
                  <div><div>Подарок выдан</div><div className="muted small">{g.awardedBy}</div></div>
                  <div className="small">{fmtDateTime(g.awardedAt)}{g.itemCodes.length > 0 && <div className="muted">Кодов маркировки: {g.itemCodes.length}</div>}</div>
                </div>
              ))}
            </div>
          )}
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
                  {stats.giftEnabled && r.awarded && r.awardedAt && (
                    <div className="muted small">{fmtTime(r.awardedAt)}{r.awardedBy ? ` · ${r.awardedBy}` : ""}</div>
                  )}
                  {stats.giftEnabled && !r.awarded && r.awardedAt && (
                    <div className="muted small">выдача снята ({fmtTime(r.awardedAt)}{r.awardedBy ? ` · ${r.awardedBy}` : ""})</div>
                  )}
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
