/** Телефон: нормализация как в v1 (только цифры, 8XXXXXXXXXX → 7XXXXXXXXXX) и маска ввода +7 (XXX) XXX-XX-XX. */

export const digitsOnly = (v: unknown): string => String(v ?? "").replace(/\D/g, "");

export function normalizePhone(v: unknown): string {
  let d = digitsOnly(v);
  if (d.length === 10) d = "7" + d;
  if (d.length === 11 && d[0] === "8") d = "7" + d.slice(1);
  return d;
}

export const isValidPhone = (normalized: string): boolean => /^7\d{10}$/.test(normalized);

/** Форматирование по мере ввода. Принимает любой ввод, возвращает "+7 (916) 123-45-67" (частично при неполном). */
export function formatPhone(input: string): string {
  let d = digitsOnly(input);
  if (d.startsWith("8")) d = "7" + d.slice(1);
  if (d.length > 0 && !d.startsWith("7")) d = "7" + d;
  d = d.slice(0, 11);
  if (d.length === 0) return "";
  let out = "+7";
  const rest = d.slice(1);
  if (rest.length > 0) out += " (" + rest.slice(0, 3);
  if (rest.length >= 3) out += ")";
  if (rest.length > 3) out += " " + rest.slice(3, 6);
  if (rest.length > 6) out += "-" + rest.slice(6, 8);
  if (rest.length > 8) out += "-" + rest.slice(8, 10);
  return out;
}

export const maskPhone = (normalized: string): string =>
  normalized.length === 11 ? `+7 ${normalized[1]}** *** ** ${normalized.slice(9)}` : "***";
