import { useEffect, useRef, useState } from "react";
import { ApiError, publicApi } from "../api/client";
import { formatPhone, isValidPhone, normalizePhone } from "../lib/phone";

export interface PhoneState {
  /** нормализованный номер, для которого получен токен */
  phone: string;
  token: string;
}

interface Props {
  eventId: string;
  value: string; // текущее значение поля (маска)
  onChange: (masked: string) => void;
  verified: PhoneState | null;
  onVerified: (s: PhoneState | null) => void;
  required?: boolean;
  invalid?: boolean;
  label: string;
}

/**
 * Поле телефона с подтверждением flash-call: ввод → «Подтвердить» → звонок → последние 4 цифры → токен.
 * Изменение номера после подтверждения сбрасывает подтверждение.
 */
export function PhoneVerify({ eventId, value, onChange, verified, onVerified, required, invalid, label }: Props) {
  const [stage, setStage] = useState<"idle" | "calling" | "code" | "verifying">("idle");
  const [pin, setPin] = useState("");
  const [error, setError] = useState("");
  const [hint, setHint] = useState("");
  const [resendIn, setResendIn] = useState(0);
  const [attemptsLeft, setAttemptsLeft] = useState<number | null>(null);
  const timer = useRef<number | null>(null);
  const pinRef = useRef<HTMLInputElement>(null);

  const normalized = normalizePhone(value);
  const isVerified = verified !== null && verified.phone === normalized;

  useEffect(() => () => { if (timer.current) window.clearInterval(timer.current); }, []);

  const startResendTimer = (seconds: number) => {
    if (timer.current) window.clearInterval(timer.current);
    setResendIn(seconds);
    timer.current = window.setInterval(() => {
      setResendIn((s) => {
        if (s <= 1) {
          if (timer.current) window.clearInterval(timer.current);
          return 0;
        }
        return s - 1;
      });
    }, 1000);
  };

  const onInput = (raw: string) => {
    const masked = formatPhone(raw);
    onChange(masked);
    if (verified && verified.phone !== normalizePhone(masked)) onVerified(null);
    if (stage !== "idle" && normalizePhone(masked) !== normalized) {
      setStage("idle");
      setPin("");
      setError("");
    }
  };

  const call = async () => {
    setError("");
    if (!isValidPhone(normalized)) {
      setError("Введите номер телефона полностью: +7 (XXX) XXX-XX-XX.");
      return;
    }
    setStage("calling");
    try {
      const r = await publicApi.call(eventId, normalized);
      if (r.status === "already_verified" && r.token) {
        onVerified({ phone: normalized, token: r.token });
        setStage("idle");
        setHint("");
        return;
      }
      setHint(r.message);
      setAttemptsLeft(r.attempts);
      setStage("code");
      startResendTimer(r.retryAfter || 60);
      window.setTimeout(() => pinRef.current?.focus(), 50);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось заказать звонок. Попробуйте ещё раз.");
      setStage("idle");
    }
  };

  const verify = async (code: string) => {
    if (code.length < 4) return;
    setStage("verifying");
    setError("");
    try {
      const r = await publicApi.verify(eventId, normalized, code);
      onVerified({ phone: normalized, token: r.token });
      setStage("idle");
      setPin("");
      setHint("");
    } catch (err) {
      const e = err as ApiError;
      setError(e.message || "Не удалось проверить код.");
      if (e instanceof ApiError && typeof e.body.attemptsLeft === "number") setAttemptsLeft(e.body.attemptsLeft as number);
      if (e instanceof ApiError && (e.code === "expired" || e.code === "too_many" || e.code === "no_call")) {
        setStage("idle");
        setPin("");
      } else {
        setStage("code");
        setPin("");
        window.setTimeout(() => pinRef.current?.focus(), 50);
      }
    }
  };

  const onPin = (raw: string) => {
    const digits = raw.replace(/\D/g, "").slice(0, 4);
    setPin(digits);
    if (digits.length === 4) void verify(digits);
  };

  return (
    <div>
      <label className={`lbl${required ? " req" : ""}`} htmlFor="phone">{label}</label>
      <input
        id="phone"
        name="phone"
        type="tel"
        inputMode="tel"
        autoComplete="tel"
        placeholder="+7 (___) ___-__-__"
        className={invalid && !isVerified ? "invalid" : ""}
        value={value}
        disabled={stage === "calling" || stage === "verifying"}
        onChange={(e) => onInput(e.target.value)}
      />
      <div className="phone-block">
        {isVerified ? (
          <div className="phone-ok">✓ Телефон подтверждён</div>
        ) : stage === "code" || stage === "verifying" ? (
          <div>
            <p className="small">{hint || "Вам поступит звонок. Введите последние 4 цифры входящего номера."}</p>
            <div className="row">
              <input
                ref={pinRef}
                className="pin"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={4}
                aria-label="Последние 4 цифры входящего номера"
                value={pin}
                disabled={stage === "verifying"}
                onChange={(e) => onPin(e.target.value)}
              />
              {stage === "verifying" && <span className="muted small">Проверяем…</span>}
            </div>
            <div className="row small mt">
              {resendIn > 0 ? (
                <span className="muted">Повторный звонок через {resendIn} с</span>
              ) : (
                <button type="button" className="ghost" onClick={call}>Позвонить ещё раз</button>
              )}
              {attemptsLeft !== null && attemptsLeft < 5 && <span className="muted">Осталось попыток: {attemptsLeft}</span>}
            </div>
          </div>
        ) : (
          <button type="button" className="secondary" disabled={stage === "calling"} onClick={call}>
            {stage === "calling" ? "Звоним…" : "Подтвердить номер"}
          </button>
        )}
        {error && <div className="alert error small">{error}</div>}
      </div>
    </div>
  );
}
