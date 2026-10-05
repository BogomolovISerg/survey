import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { ApiError, staffApi, type GiftCard } from "../api/client";
import { GiftItemCodes, type CheckedItemCode } from "./GiftItemCodes";
import { fmtDateTime } from "../lib/format";
import { Alert } from "../ui/Alert";
import { giftTokenFromScan } from "../lib/giftToken";
import { usePanel } from "./StaffLayout";
import { giftAwardVisibility } from "../lib/giftAwardMode";
import { lazy, Suspense, useCallback } from "react";

/** Код маркировки длинный — показываем начало и конец. */
const shortCode = (c: string) => (c.length > 28 ? `${c.slice(0, 18)}…${c.slice(-6)}` : c);

const QrScanner = lazy(() => import("../ui/QrScanner").then((m) => ({ default: m.QrScanner })));

/**
 * Выдача подарка. Два входа:
 *  - deep-link /staff/gift?t=<токен> — сотрудник отсканировал QR с экрана посетителя штатной камерой;
 *  - ручной ввод кода с экрана посетителя (?event=… предвыбирает мероприятие).
 */
export function GiftPage() {
  const { user: me, events: availableEvents, setEventId: selectEvent } = usePanel();
  const events = availableEvents ?? [];
  /** снятие отметки выдачи (в т.ч. отмена сразу после выдачи) доступно только администратору */
  const isAdmin = me.roles.includes("ADMIN");
  const [params, setParams] = useSearchParams();
  const token = params.get("t") ?? "";
  const [eventId, setEventId] = useState(params.get("event") ?? localStorage.getItem("survey.staff.event") ?? "");
  const [code, setCode] = useState("");
  const [card, setCard] = useState<GiftCard | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const undoTimer = useRef<number | null>(null);
  const [undoLeft, setUndoLeft] = useState(0);
  const codeRef = useRef<HTMLInputElement>(null);
  /** встроенный сканер камеры открыт */
  const [scanning, setScanning] = useState(false);
  /** отсканированные/введённые коды маркировки подарка (может быть несколько) + статус проверки в ЧЗ */
  const [itemCodes, setItemCodes] = useState<CheckedItemCode[]>([]);
  const [checkingCode, setCheckingCode] = useState(false);
  const viaScanRef = useRef(false);

  const onScanned = useCallback(
    (text: string) => {
      const t = giftTokenFromScan(text);
      setScanning(false);
      if (!t) {
        setError("Этот QR-код не похож на код подарка анкеты. Попробуйте ещё раз или введите код вручную.");
        return;
      }
      viaScanRef.current = true;
      setParams({ t }, { replace: true });
    },
    [setParams],
  );

  useEffect(() => {
    if (events.length > 0 && !events.some((e) => e.id === eventId)) setEventId(events[0].id);
    else if (events.some((e) => e.id === eventId)) selectEvent(eventId);
  }, [availableEvents, eventId, selectEvent]);

  useEffect(() => { if (card) setEventId(card.eventId); }, [card?.eventId]);
  const current = events.find((e) => e.id === eventId);

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
    const codes = awarded && itemCodes.length > 0 ? itemCodes.map((c) => c.code) : undefined;
    try {
      const q = token ? { token, awarded, itemCodes: codes } : { eventId: card.eventId, code: card.giftCode, awarded, itemCodes: codes };
      const r = await staffApi.award(q);
      setCard(r);
      const warnings = (r.markWarnings ?? []).join(" ");
      if (r.result === "ok") {
        setNotice(`Подарок выдан${codes ? `, КМ: ${codes.map(shortCode).join(", ")}` : ""}.${warnings ? " " + warnings : ""}`);
        if (isAdmin) startUndo();
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
    setItemCodes([]);
    stopUndo();
    if (token) setParams({}, { replace: true });
    // после выдачи по скану — сразу открываем камеру для следующего посетителя
    if (viaScanRef.current) setScanning(true);
    else window.setTimeout(() => codeRef.current?.focus(), 50);
  };

  return (
    <div className="page">
      {!card && !token && scanning && (
        <div className="card">
          <h2>Сканирование QR</h2>
          <Suspense fallback={<p className="muted small">Загружаем сканер…</p>}>
            <QrScanner formats="qr" hint="Наведите камеру на QR-код с экрана посетителя." onScan={onScanned} onClose={() => setScanning(false)} />
          </Suspense>
        </div>
      )}

      {!card && !token && !scanning && (
        <form className="card" onSubmit={lookupByCode}>
          <h2>Выдача с анкетой</h2>
          {giftAwardVisibility(current).manual && <Link className="btn secondary block" to={`/staff/gift/manual?event=${eventId}`}>Выдача без анкеты</Link>}
          <button type="button" className="block" onClick={() => { setError(""); setScanning(true); }}>Сканировать QR камерой</button>
          <p className="muted small mt">Или введите код, который показан у посетителя под QR-кодом.</p>
          {events.length === 1 && <p className="muted small" style={{ marginBottom: 0 }}>Мероприятие: <b>{events[0].name}</b></p>}
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
            <>
              <div className="badge ok">Подарок выдан {card.awardedAt ? fmtDateTime(card.awardedAt) : ""}{card.awardedBy ? ` · ${card.awardedBy}` : ""}</div>
              {(card.itemCodes ?? []).length > 0 && (
                <p className="small mt" style={{ marginBottom: 0 }}>
                  {(card.itemCodes ?? []).length === 1 ? "Код подарка: " : "Коды подарка: "}
                  {(card.itemCodes ?? []).map((c) => <code key={c} style={{ marginRight: 6 }}>{shortCode(c)}</code>)}
                </p>
              )}
            </>
          ) : card.giftEnabled ? (
            <div className="badge muted">Подарок ещё не выдан</div>
          ) : (
            <div className="badge warn">На этом мероприятии подарки не выдаются</div>
          )}
          {notice && <Alert kind="ok">{notice}</Alert>}
          {error && <Alert kind="error">{error}</Alert>}
          {card.giftEnabled && card.giftMarked && !card.awarded && (
            <GiftItemCodes key={card.responseId} codes={itemCodes} onChange={setItemCodes}
              required={card.giftMarkRequired} disabled={busy} checking={checkingCode} setChecking={setCheckingCode}
              onError={setError} onNotice={setNotice} />
          )}
          <div className="row mt">
            {!card.awarded && card.giftEnabled && (
              <button type="button" className="grow" disabled={busy || checkingCode || (!!card.giftMarkRequired && itemCodes.length === 0)} onClick={() => award(true)}>
                {busy ? "…" : "Выдать подарок"}
              </button>
            )}
            {isAdmin && card.awarded && undoLeft > 0 && (
              <button type="button" className="secondary grow" disabled={busy} onClick={() => award(false)}>Отменить ({undoLeft})</button>
            )}
            {isAdmin && card.awarded && undoLeft === 0 && (
              <button type="button" className="ghost" disabled={busy} onClick={() => award(false)}>Снять отметку</button>
            )}
            <button type="button" className="secondary" onClick={reset}>Следующий</button>
          </div>
        </div>
      )}
    </div>
  );
}
