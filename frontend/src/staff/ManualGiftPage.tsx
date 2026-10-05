import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { ApiError, staffApi, type ManualGiftReceipt } from "../api/client";
import { fmtDateTime } from "../lib/format";
import { Alert } from "../ui/Alert";
import { usePanel } from "./StaffLayout";
import { GiftItemCodes, type CheckedItemCode } from "./GiftItemCodes";

type PendingAward = { requestId: string; eventId: string; itemCodes: string[] };
const PENDING = "survey.staff.manualGift.pending";

/** Сохраняем запрос до отправки: после потери ответа или перезагрузки повторяем ту же выдачу. */
function readPending(): PendingAward | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(PENDING) ?? "null") as PendingAward | null;
    return value && typeof value.requestId === "string" && typeof value.eventId === "string"
      && Array.isArray(value.itemCodes) && value.itemCodes.every((c) => typeof c === "string") ? value : null;
  } catch {
    return null;
  }
}

export function ManualGiftPage() {
  const [params] = useSearchParams();
  const pending = useRef<PendingAward | null>(readPending());
  const { events, setEventId: selectEvent, eventError } = usePanel();
  const [eventId, setEventId] = useState(pending.current?.eventId ?? params.get("event") ?? localStorage.getItem("survey.staff.event") ?? "");
  const [codes, setCodes] = useState<CheckedItemCode[]>(() => (pending.current?.itemCodes ?? []).map((code) => ({ code, check: "unknown" })));
  const [busy, setBusy] = useState(false);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [receipt, setReceipt] = useState<ManualGiftReceipt | null>(null);
  const [retry, setRetry] = useState(!!pending.current);
  const current = events?.find((e) => e.id === eventId);

  useEffect(() => {
    if (!events) return;
    if (!pending.current && !events.some((e) => e.id === eventId)) setEventId(events[0]?.id ?? "");
    else if (events.some((e) => e.id === eventId)) selectEvent(eventId);
  }, [events, eventId, selectEvent]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    // ref блокирует второй клик ещё до обновления React-state.
    if (sending.current || receipt || (!retry && (!current?.giftEnabled || !current.active))) return;
    sending.current = true;
    setBusy(true);
    setError("");
    setNotice("");
    try {
      if (!pending.current) {
        const request = { requestId: crypto.randomUUID(), eventId, itemCodes: codes.map((c) => c.code) };
        sessionStorage.setItem(PENDING, JSON.stringify(request));
        pending.current = request;
      }
      const result = await staffApi.awardManual(pending.current);
      setReceipt(result);
      sessionStorage.removeItem(PENDING);
      pending.current = null;
      setRetry(false);
    } catch (err) {
      // Только явный отказ API позволяет изменить данные; при потере ответа сохраняем исходный запрос.
      const rejected = err instanceof ApiError && err.status >= 400 && err.status < 500;
      if (rejected) {
        sessionStorage.removeItem(PENDING);
        pending.current = null;
      }
      setRetry(!!pending.current);
      setError(err instanceof Error ? err.message : "Не удалось записать выдачу.");
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };
  const sending = useRef(false);

  const next = () => {
    setReceipt(null);
    setCodes([]);
    setError("");
    setNotice("");
  };

  return (
    <div className="page">
      <form className="card" onSubmit={submit}>
        <h2>Выдача без анкеты</h2>
        <p className="muted small">Выберите мероприятие и подтвердите выдачу одного подарка посетителю.</p>
        {events === null && !error && <p className="muted">Загружаем мероприятия…</p>}
        {events?.length === 0 && <Alert kind="info">Вам пока не назначено ни одного мероприятия.</Alert>}
        {(events?.length ?? 0) > 0 && (
          <>
            <label className="lbl" htmlFor="manual-event">Мероприятие</label>
            <select id="manual-event" value={eventId} disabled={busy || checking || retry || !!receipt}
              onChange={(e) => { setEventId(e.target.value); setCodes([]); setError(""); setNotice(""); }}>
              {!current && <option value={eventId}>Выбранное мероприятие недоступно</option>}
              {events?.map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
            </select>
          </>
        )}
        {current && !current.giftEnabled && !receipt && <Alert kind="info">На этом мероприятии подарки не выдаются.</Alert>}
        {retry && !receipt && <Alert kind="info">Проверяем предыдущую выдачу. Повторите запрос — второй подарок не будет записан.</Alert>}
        {notice && <Alert kind="info">{notice}</Alert>}
        {(error || eventError) && <Alert kind="error">{error || eventError}</Alert>}
        {receipt ? (
          <>
            <Alert kind="ok">Подарок выдан без анкеты: {fmtDateTime(receipt.awardedAt)} · {receipt.awardedBy}</Alert>
            {receipt.itemCodes.length > 0 && <p className="small">Коды маркировки: {receipt.itemCodes.join(", ")}</p>}
            {(receipt.markWarnings ?? []).map((w) => <Alert key={w} kind="info">{w}</Alert>)}
            <button type="button" className="block mt" onClick={next}>Следующий посетитель</button>
          </>
        ) : (
          <>
            {current?.giftEnabled && current.giftMarked && (
              <GiftItemCodes key={eventId} codes={codes} onChange={setCodes} required={current.giftMarkRequired}
                disabled={busy || retry} checking={checking} setChecking={setChecking} onError={setError} onNotice={setNotice} />
            )}
            <button className="block mt" type="submit"
              disabled={busy || checking || (!retry && (!current?.giftEnabled || !current.active || (!!current.giftMarkRequired && codes.length === 0)))}>
              {busy ? "Записываем…" : retry ? "Повторить запрос выдачи" : "Подтвердить выдачу подарка"}
            </button>
          </>
        )}
        <Link className="btn secondary block mt" to="/staff">На стенд</Link>
      </form>
    </div>
  );
}
