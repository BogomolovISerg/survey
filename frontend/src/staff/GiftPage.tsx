import { useEffect, useRef, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router";
import { ApiError, staffApi, type EventSummary, type GiftCard } from "../api/client";
import { fmtDateTime } from "../lib/format";
import { Alert } from "../ui/Alert";

/**
 * Выдача подарка. Два входа:
 *  - deep-link /staff/gift?t=<токен> — промоутер отсканировал QR с экрана посетителя штатной камерой;
 *  - ручной ввод кода с экрана посетителя (?event=… предвыбирает мероприятие).
 */
export function GiftPage() {
  const [params, setParams] = useSearchParams();
  const token = params.get("t") ?? "";
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [eventId, setEventId] = useState(params.get("event") ?? localStorage.getItem("survey.staff.event") ?? "");
  const [code, setCode] = useState("");
  const [card, setCard] = useState<GiftCard | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const undoTimer = useRef<number | null>(null);
  const [undoLeft, setUndoLeft] = useState(0);
  const codeRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    staffApi
      .events()
      .then((list) => {
        setEvents(list);
        if (list.length > 0 && !list.some((e) => e.id === eventId)) setEventId(list[0].id);
      })
      .catch(() => undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // По deep-link сразу ищем карточку
  useEffect(() => {
    if (!token) return;
    setBusy(true);
    setError("");
    setCard(null);
    staffApi
      .lookup({ token })
      .then(setCard)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Не удалось найти анкету."))
      .finally(() => setBusy(false));
  }, [token]);

  const lookupByCode = async (e: FormEvent) => {
    e.preventDefault();
    const digits = code.replace(/\D/g, "");
    if (!eventId || digits.length < 4) return;
    setBusy(true);
    setError("");
    setCard(null);
    try {
      setCard(await staffApi.lookup({ eventId, code: digits }));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось найти анкету.");
    } finally {
      setBusy(false);
    }
  };

  const award = async (awarded: boolean) => {
    if (!card) return;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const q = token ? { token, awarded } : { eventId: card.eventId, code: card.giftCode, awarded };
      const r = await staffApi.award(q);
      setCard(r);
      if (r.result === "ok") {
        setNotice("Подарок выдан.");
        startUndo();
      } else if (r.result === "already") {
        setNotice(`Подарок уже выдан${r.awardedAt ? ` ${fmtDateTime(r.awardedAt)}` : ""}${r.awardedBy ? `, ${r.awardedBy}` : ""}.`);
      } else if (r.result === "removed") {
        setNotice("Отметка о выдаче снята.");
        stopUndo();
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось отметить подарок.");
    } finally {
      setBusy(false);
    }
  };

  const startUndo = () => {
    stopUndo();
    setUndoLeft(10);
    undoTimer.current = window.setInterval(() => {
      setUndoLeft((s) => {
        if (s <= 1) {
          stopUndo();
          return 0;
        }
        return s - 1;
      });
    }, 1000);
  };
  const stopUndo = () => {
    if (undoTimer.current) window.clearInterval(undoTimer.current);
    undoTimer.current = null;
    setUndoLeft(0);
  };
  useEffect(() => () => stopUndo(), []);

  const reset = () => {
    setCard(null);
    setCode("");
    setError("");
    setNotice("");
    stopUndo();
    if (token) setParams({}, { replace: true });
    window.setTimeout(() => codeRef.current?.focus(), 50);
  };

  return (
    <div className="page">
      {!card && !token && (
        <form className="card" onSubmit={lookupByCode}>
          <h2>Выдача подарка</h2>
          <p className="muted small">Наведите камеру телефона на QR-код с экрана посетителя — откроется карточка. Или введите код, который показан под QR-кодом.</p>
          {events.length > 1 && (
            <>
              <label className="lbl" htmlFor="gev">Мероприятие</label>
              <select id="gev" value={eventId} onChange={(e) => setEventId(e.target.value)}>
                {events.map((e) => (
                  <option key={e.id} value={e.id}>{e.name}</option>
                ))}
              </select>
            </>
          )}
          <label className="lbl" htmlFor="gcode">Код с экрана посетителя</label>
          <input
            ref={codeRef}
            id="gcode"
            className="code-input"
            type="text"
            inputMode="numeric"
            autoComplete="off"
            maxLength={8}
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
          />
          {error && <Alert kind="error">{error}</Alert>}
          <button className="block mt" type="submit" disabled={busy || !eventId || code.length < 4}>{busy ? "Ищем…" : "Найти"}</button>
        </form>
      )}

      {token && !card && (
        <div className="card">
          {busy && <p className="muted">Ищем анкету по QR-коду…</p>}
          {error && <Alert kind="error">{error}</Alert>}
          {!busy && <button type="button" className="secondary" onClick={reset}>Ввести код вручную</button>}
        </div>
      )}

      {card && (
        <div className="card staff-card">
          <div className="muted small">{card.eventName}</div>
          <div className="visitor">{card.visitor}</div>
          <div className="muted">{card.city}</div>
          <p className="small mt">Анкета от {fmtDateTime(card.submittedAt)} · код {card.giftCode}</p>
          {card.awarded ? (
            <div className="badge ok">Подарок выдан {card.awardedAt ? fmtDateTime(card.awardedAt) : ""}{card.awardedBy ? ` · ${card.awardedBy}` : ""}</div>
          ) : card.giftEnabled ? (
            <div className="badge muted">Подарок ещё не выдан</div>
          ) : (
            <div className="badge warn">На этом мероприятии подарки не выдаются</div>
          )}
          {notice && <Alert kind="ok">{notice}</Alert>}
          {error && <Alert kind="error">{error}</Alert>}
          <div className="row mt">
            {!card.awarded && card.giftEnabled && (
              <button type="button" className="grow" disabled={busy} onClick={() => award(true)}>{busy ? "…" : "Выдать подарок"}</button>
            )}
            {card.awarded && undoLeft > 0 && (
              <button type="button" className="secondary grow" disabled={busy} onClick={() => award(false)}>Отменить ({undoLeft})</button>
            )}
            {card.awarded && undoLeft === 0 && (
              <button type="button" className="ghost" disabled={busy} onClick={() => award(false)}>Снять отметку</button>
            )}
            <button type="button" className="secondary" onClick={reset}>Следующий</button>
          </div>
        </div>
      )}
    </div>
  );
}
