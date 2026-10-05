import { lazy, Suspense, useCallback, useState, type Dispatch, type SetStateAction } from "react";
import { staffApi, type ItemCodeCheck } from "../api/client";

export type CheckedItemCode = { code: string; check: ItemCodeCheck["status"]; message?: string };
const shortCode = (c: string) => (c.length > 28 ? `${c.slice(0, 18)}…${c.slice(-6)}` : c);
const QrScanner = lazy(() => import("../ui/QrScanner").then((m) => ({ default: m.QrScanner })));

/** Общий ввод и проверка маркировки для выдачи по анкете и без анкеты. */
export function GiftItemCodes({ codes, onChange, required, disabled, checking, setChecking, onError, onNotice }: {
  codes: CheckedItemCode[];
  onChange: Dispatch<SetStateAction<CheckedItemCode[]>>;
  required?: boolean;
  disabled: boolean;
  checking: boolean;
  setChecking: Dispatch<SetStateAction<boolean>>;
  onError: Dispatch<SetStateAction<string>>;
  onNotice: Dispatch<SetStateAction<string>>;
}) {
  const [scanning, setScanning] = useState(false);
  const [itemCodeManual, setItemCodeManual] = useState("");
  /** Скан/ввод КМ: онлайн-проверка в «Честном знаке». «Не в обороте» у сотрудника с блокировкой — код не принимается. */
  const onGiftScanned = useCallback((text: string) => {
    if (disabled || checking) return;
    const cleaned = text.replace(/[\u001d\u00e8]/g, "").trim();
    setScanning(false);
    onError("");
    if (!cleaned) return;
    setChecking(true);
    staffApi
      .checkItemCode(cleaned)
      .then((r) => {
        if (r.status === "rejected" && r.blocking) {
          onError(`Код не принят: ${r.message ?? "не в обороте"}`);
          return;
        }
        if (r.status === "rejected") onNotice(`Внимание: ${r.message ?? "код не в обороте."} Выдача не заблокирована.`);
        onChange((prev) => (prev.some((c) => c.code === cleaned) ? prev : [...prev, { code: cleaned, check: r.status, message: r.message }]));
      })
      .catch(() => {
        // сервис проверки недоступен — код принимаем как непроверенный, решение примет сервер при выдаче
        onChange((prev) => (prev.some((c) => c.code === cleaned) ? prev : [...prev, { code: cleaned, check: "unknown" }]));
      })
      .finally(() => setChecking(false));
  }, [disabled, checking, onError, onNotice, onChange, setChecking]);

  return (
            <div className="card mt" style={{ marginBottom: 0 }}>
              <h3>Код маркировки подарка{required ? " *" : ""}</h3>
              {codes.map((c) => (
                <p className="small" key={c.code} style={{ marginBottom: 4 }}>
                  {c.check === "in_circulation" && <span className="badge ok">КМ · в обороте</span>}
                  {c.check === "rejected" && <span className="badge warn">КМ · не в обороте</span>}
                  {c.check === "unknown" && <span className="badge muted">КМ · не проверен</span>}
                  {c.check === "disabled" && <span className="badge ok">КМ</span>}{" "}
                  <code>{shortCode(c.code)}</code>{" "}
                  <button type="button" className="ghost sm" disabled={disabled || checking} onClick={() => onChange((prev) => prev.filter((x) => x.code !== c.code))}>убрать</button>
                  {(c.check === "rejected" || c.check === "unknown") && c.message && (
                    <span className="muted small" style={{ display: "block" }}>{c.message}</span>
                  )}
                </p>
              ))}
              {checking && <p className="muted small">Проверяем код в «Честном знаке»…</p>}
              {scanning && !disabled ? (
                <Suspense fallback={<p className="muted small">Загружаем сканер…</p>}>
                  <QrScanner formats="any" hint="Отсканируйте DataMatrix / QR с упаковки подарка." onScan={onGiftScanned} onClose={() => setScanning(false)} />
                </Suspense>
              ) : (
                <>
                  <button type="button" className="secondary block" disabled={disabled || checking} onClick={() => { onError(""); setScanning(true); }}>
                    {codes.length > 0 ? "Отсканировать ещё код" : "Отсканировать код маркировки"}
                  </button>
                  {required && codes.length === 0 && (
                    <p className="muted small mt" style={{ marginBottom: 0 }}>Без кода маркировки выдача подарка недоступна.</p>
                  )}
                </>
              )}
              <label className="lbl" htmlFor="item-code">{scanning ? "Или введите код вручную" : "Ввести код вручную (без камеры)"}</label>
              <div className="row">
                <input id="item-code" type="text" className="grow" disabled={disabled || checking} autoComplete="off" value={itemCodeManual}
                  onChange={(e) => setItemCodeManual(e.target.value)} placeholder="код с упаковки" />
                <button type="button" className="secondary" disabled={disabled || checking || !itemCodeManual.trim()}
                  onClick={() => { onGiftScanned(itemCodeManual); setItemCodeManual(""); }}>OK</button>
              </div>
            </div>
  );
}
