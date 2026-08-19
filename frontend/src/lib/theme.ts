/**
 * Тема анкеты: light | dark. Приоритет: ?theme= в адресе → выбор пользователя (localStorage) → тема мероприятия/схемы → light.
 * Применяется атрибутом data-theme на <html>; палитра — в styles.css.
 */
const STORAGE_KEY = "survey.theme";
const THEMES = ["light", "dark"] as const;
export type Theme = (typeof THEMES)[number];

let memoryTheme = "";

const normalize = (value: unknown): Theme | "" => {
  const v = String(value ?? "").trim().toLowerCase();
  if (["inverse", "inverted", "black", "тёмная", "темная"].includes(v)) return "dark";
  return (THEMES as readonly string[]).includes(v) ? (v as Theme) : "";
};

const storage = (): Storage | null => {
  try {
    return typeof window !== "undefined" ? window.localStorage : null;
  } catch {
    return null;
  }
};

export const readUrlTheme = (search = typeof window !== "undefined" ? window.location.search : ""): Theme | "" =>
  normalize(new URLSearchParams(search).get("theme"));

const readStoredTheme = (): Theme | "" => {
  const ls = storage();
  if (ls) {
    try {
      return normalize(ls.getItem(STORAGE_KEY));
    } catch {
      /* ignore */
    }
  }
  return normalize(memoryTheme);
};

const store = (value: Theme) => {
  memoryTheme = value;
  const ls = storage();
  if (ls) {
    try {
      ls.setItem(STORAGE_KEY, value);
    } catch {
      /* ignore */
    }
  }
};

export function currentTheme(): Theme {
  return (document.documentElement.getAttribute("data-theme") as Theme) || "light";
}

export function applyTheme(value: unknown, { persist = false } = {}): Theme {
  const next = normalize(value) || "light";
  document.documentElement.setAttribute("data-theme", next);
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", next === "dark" ? "#111214" : "#ffffff");
  if (persist) store(next);
  return next;
}

/** Начальная тема до загрузки схемы: адрес → сохранённый выбор → light */
export const initTheme = (): Theme => applyTheme(readUrlTheme() || readStoredTheme() || "light");

/** Тема из мероприятия/схемы. Не перебивает явный выбор в адресе или сохранённый пользователем. */
export function applyServerTheme(styles: { theme?: unknown; invertLogoOnDark?: unknown } | undefined, eventTheme?: { mode?: unknown; accent?: unknown; invertLogoOnDark?: unknown }) {
  const mode = eventTheme?.mode ?? styles?.theme;
  if (!readUrlTheme() && !readStoredTheme() && normalize(mode)) applyTheme(mode);
  const invert = eventTheme?.invertLogoOnDark ?? styles?.invertLogoOnDark;
  document.documentElement.setAttribute("data-logo-invert", invert === false ? "off" : "on");
  const accent = typeof eventTheme?.accent === "string" && /^#[0-9a-fA-F]{6}$/.test(eventTheme.accent) ? eventTheme.accent : "";
  if (accent) document.documentElement.style.setProperty("--accent", accent);
}

export const toggleTheme = (): Theme => applyTheme(currentTheme() === "dark" ? "light" : "dark", { persist: true });
