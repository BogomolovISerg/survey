/**
 * Схема анкеты (формат v1, приходит из 1С через сервис):
 *   enums:      { "<имя списка>": ["значение", ...] }
 *   components: { "<имя>": { header, conditions, required: [], data: [ { "<поле>": FieldDef } | "{Компонент}" ], style } }
 *   schema:     ["{Компонент}", ...] — порядок компонентов
 *   style:      { theme?: "light"|"dark", invertLogoOnDark?: boolean }
 *   output:     { "<поле>": "" } — заготовка ответов
 *   event:      { id, name, gift, active, theme? } — добавляет сервис
 */
import type { Condition } from "./conditions";

export interface FieldDef {
  element: "image" | "text" | "inputbox" | "radio" | "checkbox" | string;
  label?: string;
  conditions?: Condition;
  mask?: "phone" | "email" | string;
  type?: "enum" | "boolean" | string;
  values?: string; // имя списка enums для radio/checkbox; data-URL для image
  value?: string; // текст для element=text
  data?: string; // data-URL для image (v2)
  placeholder?: string;
}

export type DataElement = Record<string, FieldDef> | { component: string; ref: Component };

export interface Component {
  header: string;
  conditions: Condition;
  required: string[];
  data: DataElement[];
  style?: unknown;
}

export interface EventInfo {
  id: string;
  name: string;
  gift: boolean;
  active: boolean;
  theme?: { mode?: string; accent?: string; invertLogoOnDark?: boolean };
}

export interface Schema {
  enums: Record<string, string[]>;
  components: Record<string, Component>;
  order: { name: string; component: Component }[];
  styles: Record<string, unknown>;
  output: Record<string, unknown>;
  event: EventInfo;
  version: number;
}

export class SchemaError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SchemaError";
  }
}

const isPlainObject = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === "object" && !Array.isArray(v);

/** "{Идентификация}" → "Идентификация" */
export const refName = (value: unknown): string => String(value).replace(/[^а-яА-ЯёЁa-zA-Z0-9_]/g, "");

export function isComponentRef(el: DataElement): el is { component: string; ref: Component } {
  return isPlainObject(el) && typeof (el as { component?: unknown }).component === "string" && "ref" in el;
}

function normalizeComponents(input: Record<string, unknown>): Record<string, Component> {
  const out: Record<string, Component> = {};
  for (const [name, raw] of Object.entries(input)) {
    if (!isPlainObject(raw)) throw new SchemaError(`Компонент «${name}» имеет неверный формат`);
    out[name] = {
      header: typeof raw.header === "string" ? raw.header : "",
      conditions: (raw.conditions as Condition) ?? "",
      required: Array.isArray(raw.required) ? raw.required.map(String) : [],
      data: [],
      style: raw.style,
    };
  }
  // ссылки "{Компонент}" внутри data → { component, ref }
  for (const [name, raw] of Object.entries(input)) {
    const data = Array.isArray((raw as Record<string, unknown>).data) ? ((raw as Record<string, unknown>).data as unknown[]) : [];
    out[name].data = data.map((el) => {
      if (typeof el === "string") {
        const key = refName(el);
        if (!(key in out)) throw new SchemaError(`Компонент «${name}» ссылается на неизвестный компонент «${key}»`);
        return { component: key, ref: out[key] };
      }
      if (!isPlainObject(el)) throw new SchemaError(`Компонент «${name}»: элемент data должен быть объектом`);
      return el as Record<string, FieldDef>;
    });
  }
  return out;
}

export function parseSchema(input: unknown): Schema {
  if (!isPlainObject(input)) throw new SchemaError("Сервер вернул не анкету");
  if (typeof input.message === "string" && !input.components) throw new SchemaError(input.message);
  const componentsIn = isPlainObject(input.components) ? input.components : {};
  if (Object.keys(componentsIn).length === 0) throw new SchemaError("В анкете нет компонентов");
  const orderIn = Array.isArray(input.schema) ? input.schema : [];
  if (orderIn.length === 0) throw new SchemaError("В анкете не задан порядок компонентов (schema)");

  const components = normalizeComponents(componentsIn);
  const order = orderIn.map((ref) => {
    const name = typeof ref === "string" ? refName(ref) : isPlainObject(ref) ? Object.keys(ref)[0] : "";
    if (!(name in components)) throw new SchemaError(`Схема ссылается на неизвестный компонент «${name}»`);
    return { name, component: components[name] };
  });

  const enums: Record<string, string[]> = {};
  if (isPlainObject(input.enums)) {
    for (const [k, v] of Object.entries(input.enums)) enums[k] = Array.isArray(v) ? v.map(String) : [];
  }
  const styles = isPlainObject(input.styles) ? input.styles : isPlainObject(input.style) ? input.style : {};
  const output: Record<string, unknown> = isPlainObject(input.output) ? { ...input.output } : {};
  // enum-checkbox → массив, boolean-checkbox → boolean
  for (const component of Object.values(components)) {
    for (const el of component.data) {
      if (isComponentRef(el)) continue;
      for (const [field, def] of Object.entries(el)) {
        if (!isPlainObject(def)) continue;
        if (def.element === "checkbox" && def.type !== "boolean" && !Array.isArray(output[field])) output[field] = [];
        if (def.element === "checkbox" && def.type === "boolean" && typeof output[field] !== "boolean")
          output[field] = String(output[field] ?? "").toLowerCase() === "true";
      }
    }
  }
  const ev = isPlainObject(input.event) ? input.event : {};
  const event: EventInfo = {
    id: typeof ev.id === "string" ? ev.id : "",
    name: typeof ev.name === "string" ? ev.name : "",
    gift: ev.gift === true,
    active: ev.active !== false,
    theme: isPlainObject(ev.theme) ? (ev.theme as EventInfo["theme"]) : undefined,
  };
  return { enums, components, order, styles, output, event, version: typeof input.version === "number" ? input.version : 0 };
}

/** Ответы в формате, который ждёт ERP: массивы мультивыбора — через запятую; остальное как есть. */
export function buildPayload(output: Record<string, unknown>): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(output)) payload[key] = Array.isArray(value) ? value.join(", ") : value;
  return payload;
}

/** Определение поля по имени в компоненте (с учётом одноимённых полей с разными условиями — берём видимое). */
export function findField(component: Component, name: string, answers: Record<string, unknown>, visible: (d: FieldDef) => boolean): FieldDef | null {
  let found: FieldDef | null = null;
  for (const el of component.data) {
    if (isComponentRef(el)) continue;
    const def = el[name];
    if (!def) continue;
    if (visible(def)) return def;
    if (!found) found = def;
  }
  void answers;
  return found;
}

export const isEmptyAnswer = (v: unknown): boolean => {
  if (v === undefined || v === null) return true;
  if (typeof v === "string") return v.trim() === "";
  if (Array.isArray(v)) return v.length === 0;
  if (typeof v === "boolean") return !v;
  return false;
};
