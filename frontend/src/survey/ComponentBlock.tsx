import { useState } from "react";
import { isVisible } from "../lib/conditions";
import { isComponentRef, isEmptyAnswer, type Component, type FieldDef, type Schema } from "../lib/schema";
import { normalizePhone } from "../lib/phone";
import { PhoneVerify, type PhoneState } from "./PhoneVerify";

export const isPhoneField = (def: FieldDef, name: string) => def.mask === "phone" || name === "Телефон";

interface Props {
  eventId: string;
  name: string;
  component: Component;
  schema: Schema;
  answers: Record<string, unknown>;
  setAnswer: (key: string, value: unknown) => void;
  phone: PhoneState | null;
  setPhone: (s: PhoneState | null) => void;
  /** блок уже пройден (кнопка «Далее» не нужна) */
  passed: boolean;
  onNext: () => void;
  depth?: number;
}

/** Обязательные поля компонента, не заполненные при текущих ответах (скрытые по условию — не обязательны). */
export function missingRequired(component: Component, answers: Record<string, unknown>): string[] {
  const missing: string[] = [];
  const defs = new Map<string, FieldDef>();
  for (const el of component.data) {
    if (isComponentRef(el)) {
      if (isVisible(el.ref, answers)) missing.push(...missingRequired(el.ref, answers));
      continue;
    }
    for (const [field, def] of Object.entries(el)) {
      const existing = defs.get(field);
      if (!existing || !isVisible(existing, answers)) defs.set(field, def);
    }
  }
  for (const name of component.required) {
    const def = defs.get(name);
    if (def && !isVisible(def, answers)) continue;
    if (isEmptyAnswer(answers[name]) && !missing.includes(name)) missing.push(name);
  }
  return missing;
}

/** В компоненте (с вложенными) есть поле телефона? */
export function hasPhoneField(component: Component): boolean {
  return component.data.some((el) => (isComponentRef(el) ? hasPhoneField(el.ref) : Object.entries(el).some(([n, d]) => isPhoneField(d, n))));
}

export function ComponentBlock(props: Props) {
  const { eventId, name, component, schema, answers, setAnswer, phone, setPhone, passed, onNext, depth = 0 } = props;
  const [invalid, setInvalid] = useState<Set<string>>(new Set());
  const [message, setMessage] = useState("");

  const singleChoice =
    component.data.length === 1 &&
    !isComponentRef(component.data[0]) &&
    Object.values(component.data[0]).every((d) => d.element === "radio");

  const validate = (): boolean => {
    const missing = missingRequired(component, answers);
    const needPhone = hasPhoneField(component) && (!phone || phone.phone !== normalizePhone(answers["Телефон"] ?? findPhoneValue()));
    setInvalid(new Set(missing));
    if (needPhone && !missing.includes("Телефон")) {
      setMessage("Подтвердите номер телефона, чтобы продолжить: нажмите «Подтвердить номер» и введите цифры из звонка.");
      scrollToProblem();
      return false;
    }
    if (missing.length > 0) {
      setMessage("Заполните обязательные поля, отмеченные *.");
      scrollToProblem();
      return false;
    }
    setMessage("");
    return true;
  };

  const findPhoneValue = (): unknown => {
    for (const el of component.data) {
      if (isComponentRef(el)) continue;
      for (const [n, d] of Object.entries(el)) if (isPhoneField(d, n)) return answers[n];
    }
    return "";
  };

  const next = () => {
    if (validate()) onNext();
  };

  const onRadio = (field: string, value: string) => {
    setAnswer(field, value);
    setInvalid((s) => {
      if (!s.has(field)) return s;
      const c = new Set(s);
      c.delete(field);
      return c;
    });
    if (singleChoice && !passed) {
      // единственный переключатель сам ведёт дальше
      const remaining = missingRequired(component, { ...answers, [field]: value });
      if (remaining.length === 0) onNext();
    }
  };

  const onCheck = (field: string, value: string, checked: boolean) => {
    const list = Array.isArray(answers[field]) ? [...(answers[field] as string[])] : [];
    const idx = list.indexOf(value);
    if (checked && idx === -1) list.push(value);
    if (!checked && idx !== -1) list.splice(idx, 1);
    setAnswer(field, list);
  };

  const renderField = (fieldName: string, def: FieldDef) => {
    if (!isVisible(def, answers)) return null;
    const required = component.required.includes(fieldName);
    const bad = invalid.has(fieldName);
    switch (def.element) {
      case "image": {
        const src = def.data || def.values || def.value || "";
        return src ? (
          <div className="logo" key={fieldName}>
            <img src={src} alt={def.label || ""} />
          </div>
        ) : null;
      }
      case "text":
        return (
          <p key={fieldName} className="mt">{def.value || def.label || ""}</p>
        );
      case "inputbox": {
        if (isPhoneField(def, fieldName)) {
          return (
            <PhoneVerify
              key={fieldName}
              eventId={eventId}
              label={def.label || fieldName}
              required={required}
              invalid={bad || (message !== "" && !phone)}
              value={String(answers[fieldName] ?? "")}
              onChange={(v) => setAnswer(fieldName, v)}
              verified={phone}
              onVerified={setPhone}
            />
          );
        }
        const isEmail = def.mask === "email" || fieldName.toLowerCase() === "email";
        return (
          <div key={fieldName}>
            <label className={`lbl${required ? " req" : ""}`} htmlFor={`f-${fieldName}`}>{def.label || fieldName}</label>
            <input
              id={`f-${fieldName}`}
              name={fieldName}
              type={isEmail ? "email" : "text"}
              inputMode={isEmail ? "email" : undefined}
              autoComplete={isEmail ? "email" : fieldName === "Имя" ? "given-name" : fieldName === "Фамилия" ? "family-name" : undefined}
              placeholder={def.placeholder || ""}
              className={bad ? "invalid" : ""}
              value={String(answers[fieldName] ?? "")}
              onChange={(e) => setAnswer(fieldName, e.target.value)}
            />
          </div>
        );
      }
      case "radio": {
        const values = schema.enums[def.values || ""] || [];
        if (values.length === 0) return null;
        return (
          <div key={fieldName} className={`choice${bad ? " invalid" : ""}`} role="radiogroup" aria-label={def.label || fieldName}>
            {def.label && <label className={`lbl${required ? " req" : ""}`}>{def.label}</label>}
            {values.map((v) => (
              <label key={v} className={`opt${answers[fieldName] === v ? " on" : ""}`}>
                <input type="radio" name={`${depth}-${fieldName}`} checked={answers[fieldName] === v} onChange={() => onRadio(fieldName, v)} />
                <span>{v}</span>
              </label>
            ))}
          </div>
        );
      }
      case "checkbox": {
        if (def.type === "boolean") {
          return (
            <label key={fieldName} className={`opt${answers[fieldName] ? " on" : ""}`}>
              <input type="checkbox" checked={Boolean(answers[fieldName])} onChange={(e) => setAnswer(fieldName, e.target.checked)} />
              <span>{def.label || fieldName}</span>
            </label>
          );
        }
        const values = schema.enums[def.values || ""] || [];
        if (values.length === 0) return null;
        const selected = Array.isArray(answers[fieldName]) ? (answers[fieldName] as string[]) : [];
        return (
          <div key={fieldName} className={`choice${bad ? " invalid" : ""}`} role="group" aria-label={def.label || fieldName}>
            {def.label && <label className={`lbl${required ? " req" : ""}`}>{def.label}</label>}
            {values.map((v) => (
              <label key={v} className={`opt${selected.includes(v) ? " on" : ""}`}>
                <input type="checkbox" checked={selected.includes(v)} onChange={(e) => onCheck(fieldName, v, e.target.checked)} />
                <span>{v}</span>
              </label>
            ))}
          </div>
        );
      }
      default:
        return null;
    }
  };

  return (
    <section className="card" data-component={name}>
      {component.header && <h2>{component.header}</h2>}
      {component.data.map((el, i) => {
        if (isComponentRef(el)) {
          if (!isVisible(el.ref, answers)) return null;
          return (
            <ComponentBlock
              key={`${el.component}-${i}`}
              {...props}
              name={el.component}
              component={el.ref}
              passed={true}
              onNext={() => undefined}
              depth={depth + 1}
            />
          );
        }
        return Object.entries(el).map(([fieldName, def]) => renderField(fieldName, def));
      })}
      {message && <div className="alert error small">{message}</div>}
      {!passed && !singleChoice && depth === 0 && (
        <button type="button" className="block mt" onClick={next}>Далее</button>
      )}
    </section>
  );
}

function scrollToProblem() {
  window.setTimeout(() => {
    const el = document.querySelector(".invalid, .alert.error");
    if (el && "scrollIntoView" in el) el.scrollIntoView({ behavior: "smooth", block: "center" });
  }, 30);
}
