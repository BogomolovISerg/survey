import { useCallback, useEffect, useMemo, useState } from "react";
import { useParams } from "react-router";
import { ApiError, publicApi, type GiftInfo } from "../api/client";
import { isVisible } from "../lib/conditions";
import { buildPayload, parseSchema, SchemaError, type Schema } from "../lib/schema";
import { applyServerTheme } from "../lib/theme";
import { Alert } from "../ui/Alert";
import { ThemeToggle } from "../ui/ThemeToggle";
import { ComponentBlock, hasPhoneField, missingRequired } from "./ComponentBlock";
import { GiftScreen } from "./GiftScreen";
import type { PhoneState } from "./PhoneVerify";

const CONSENT_TEXT = "Нажимая «Отправить», вы даёте согласие на обработку персональных данных.";

const storageKey = (eventId: string) => `survey.phone.${eventId}`;

function loadPhone(eventId: string): PhoneState | null {
  try {
    const raw = window.sessionStorage.getItem(storageKey(eventId));
    if (!raw) return null;
    const p = JSON.parse(raw) as PhoneState & { exp: number };
    return p.exp > Date.now() ? { phone: p.phone, token: p.token } : null;
  } catch {
    return null;
  }
}

function savePhone(eventId: string, p: PhoneState | null) {
  try {
    if (!p) window.sessionStorage.removeItem(storageKey(eventId));
    else window.sessionStorage.setItem(storageKey(eventId), JSON.stringify({ ...p, exp: Date.now() + 25 * 60 * 1000 }));
  } catch {
    /* ignore */
  }
}

/** Публичная анкета: /e/{eventId}. */
export function SurveyPage() {
  const { eventId = "" } = useParams();
  const [state, setState] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState("");
  const [schema, setSchema] = useState<Schema | null>(null);
  const [answers, setAnswers] = useState<Record<string, unknown>>({});
  const [currIdx, setCurrIdx] = useState(0);
  const [phone, setPhoneState] = useState<PhoneState | null>(null);
  const [consent, setConsent] = useState(false);
  const [submit, setSubmit] = useState<"idle" | "submitting" | "done" | "error">("idle");
  const [submitError, setSubmitError] = useState("");
  const [gift, setGift] = useState<GiftInfo | null>(null);
  const [already, setAlready] = useState(false);

  const setPhone = useCallback(
    (p: PhoneState | null) => {
      setPhoneState(p);
      savePhone(eventId, p);
    },
    [eventId],
  );

  const load = useCallback(async () => {
    setState("loading");
    setError("");
    if (!eventId) {
      setState("error");
      setError("В адресе анкеты не указан идентификатор мероприятия. Отсканируйте QR-код ещё раз.");
      return;
    }
    try {
      const parsed = parseSchema(await publicApi.schema(eventId));
      applyServerTheme(parsed.styles as { theme?: unknown }, parsed.event.theme);
      setSchema(parsed);
      setAnswers(parsed.output);
      setCurrIdx(0);
      setPhoneState(loadPhone(eventId));
      document.title = parsed.event.name ? `Анкета — ${parsed.event.name}` : "Анкета";
      setState("ready");
    } catch (err) {
      setState("error");
      if (err instanceof ApiError && err.status === 410) setError("Мероприятие завершено, анкета закрыта. Спасибо за интерес!");
      else if (err instanceof ApiError && err.status === 404) setError("Анкета не найдена. Отсканируйте QR-код ещё раз.");
      else setError(err instanceof SchemaError || err instanceof ApiError ? err.message : "Не удалось загрузить анкету.");
    }
  }, [eventId]);

  useEffect(() => {
    void load();
  }, [load]);

  const setAnswer = useCallback((key: string, value: unknown) => setAnswers((a) => ({ ...a, [key]: value })), []);

  /** Видимые компоненты по порядку схемы (скрытые по условию пропускаются и не считаются шагом). */
  const visibleOrder = useMemo(() => (schema ? schema.order.filter((o) => isVisible(o.component, answers)) : []), [schema, answers]);
  const allPassed = schema !== null && currIdx >= visibleOrder.length;

  const onNext = () => {
    setCurrIdx((i) => i + 1);
    window.setTimeout(() => {
      const blocks = document.querySelectorAll("section.card");
      const last = blocks[blocks.length - 1];
      if (last && "scrollIntoView" in last) last.scrollIntoView({ behavior: "smooth", block: "start" });
    }, 40);
  };

  const canSubmit = allPassed && consent && submit !== "submitting" && phone !== null;

  const send = async () => {
    if (!schema || !phone) return;
    // финальная проверка всех блоков
    for (const o of visibleOrder) {
      if (missingRequired(o.component, answers).length > 0) {
        setSubmitError("Заполните обязательные поля, отмеченные *.");
        return;
      }
    }
    setSubmit("submitting");
    setSubmitError("");
    try {
      const r = await publicApi.submit(eventId, phone.token, buildPayload(answers), consent);
      setGift(r.gift);
      setSubmit("done");
      window.scrollTo({ top: 0, behavior: "smooth" });
    } catch (err) {
      if (err instanceof ApiError && err.code === "already_submitted") {
        setGift((err.body.gift as GiftInfo) ?? null);
        setAlready(true);
        setSubmit("done");
        window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      if (err instanceof ApiError && err.status === 403) {
        setPhone(null);
        setSubmitError("Подтверждение телефона устарело — подтвердите номер ещё раз и отправьте анкету.");
      } else {
        setSubmitError(err instanceof ApiError ? err.message : "Не удалось отправить анкету. Попробуйте ещё раз.");
      }
      setSubmit("error");
    }
  };

  const needsPhone = schema ? schema.order.some((o) => hasPhoneField(o.component)) : true;

  return (
    <div className="page">
      <div className="row" style={{ justifyContent: "flex-end" }}>
        <ThemeToggle />
      </div>

      {state === "loading" && <div className="status-box muted">Загрузка анкеты…</div>}

      {state === "error" && (
        <div className="status-box">
          <p>{error}</p>
          {eventId && <button type="button" className="secondary" onClick={load}>Повторить</button>}
          <p className="build-info">{import.meta.env.MODE} · {import.meta.env.BASE_URL}</p>
        </div>
      )}

      {state === "ready" && schema && submit === "done" && (
        <>
          <section className="card center">
            <h1>{already ? "Анкета уже заполнена" : "Спасибо!"}</h1>
            <p className="muted">
              {already ? "С этого номера телефона анкета уже отправлена — повторно заполнять не нужно." : "Ваши ответы отправлены."}
            </p>
          </section>
          {gift && phone && <GiftScreen eventId={eventId} eventName={schema.event.name} gift={gift} token={phone.token} />}
        </>
      )}

      {state === "ready" && schema && submit !== "done" && (
        <>
          {schema.event.name && <h1 className="center">{schema.event.name}</h1>}
          <div className="progress" aria-hidden="true">
            <div style={{ width: `${Math.min(100, Math.round(((allPassed ? visibleOrder.length : currIdx) / Math.max(1, visibleOrder.length)) * 100))}%` }} />
          </div>
          {visibleOrder.map((o, idx) =>
            idx <= currIdx ? (
              <ComponentBlock
                key={o.name}
                eventId={eventId}
                name={o.name}
                component={o.component}
                schema={schema}
                answers={answers}
                setAnswer={setAnswer}
                phone={phone}
                setPhone={setPhone}
                passed={idx < currIdx}
                onNext={onNext}
              />
            ) : null,
          )}
          {allPassed && (
            <section className="card">
              <label className="consent">
                <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
                <span>{CONSENT_TEXT}</span>
              </label>
              {needsPhone && !phone && <Alert kind="error">Подтвердите номер телефона в анкете, чтобы отправить.</Alert>}
              {submitError && <Alert kind="error">{submitError}</Alert>}
              <button type="button" className="block" disabled={!canSubmit} onClick={send}>
                {submit === "submitting" ? "Отправляем…" : "Отправить"}
              </button>
            </section>
          )}
        </>
      )}
    </div>
  );
}
