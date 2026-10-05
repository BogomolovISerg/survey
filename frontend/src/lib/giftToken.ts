/** Из распознанного текста достаёт токен подарка: ссылка …/staff/gift?t=<токен> либо сам токен (g.…). */
export function giftTokenFromScan(text: string): string | null {
  const trimmed = text.trim();
  if (trimmed.startsWith("g.")) return trimmed;
  try {
    const url = new URL(trimmed);
    const t = url.searchParams.get("t");
    if (t && t.startsWith("g.")) return t;
  } catch {
    /* не URL */
  }
  return null;
}
