/** HTTP-клиент к API сервиса. Базовый путь берётся из base сборки (контекст /survey/). */

export class ApiError extends Error {
  status: number;
  code: string;
  body: Record<string, unknown>;
  constructor(status: number, body: Record<string, unknown>) {
    super(typeof body.message === "string" ? body.message : `Ошибка ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.code = typeof body.error === "string" ? body.error : "error";
    this.body = body;
  }
}

const trimSlash = (s: string) => (s.endsWith("/") ? s.slice(0, -1) : s);

/** Базовый путь приложения без завершающего слэша: "" при base "/" или "/survey". */
export const APP_BASE = trimSlash(import.meta.env.BASE_URL || "/");
export const API_BASE = `${APP_BASE}/api/v1`;

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  headers.set("Accept", "application/json");
  let response: Response;
  try {
    response = await fetch(API_BASE + path, { credentials: "same-origin", ...init, headers });
  } catch {
    throw new ApiError(0, { error: "network", message: "Нет связи с сервером. Проверьте интернет и попробуйте ещё раз." });
  }
  const text = await response.text();
  let data: Record<string, unknown> = {};
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { error: "bad_response", message: `Некорректный ответ сервера (${response.status}).` };
    }
  }
  if (!response.ok) throw new ApiError(response.status, data);
  return data as T;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: "PATCH", body: JSON.stringify(body ?? {}) }),
};

// ---------- типы ответов ----------

export interface GiftInfo {
  enabled: boolean;
  awarded: boolean;
  awardedAt?: string;
  giftToken?: string;
  giftCode?: string;
  giftUrl?: string;
}

export interface CallResult {
  status: "called" | "already_verified" | "wait";
  message: string;
  ttl: number;
  attempts: number;
  retryAfter: number;
  token?: string;
}

export interface VerifyResult {
  verified: boolean;
  token: string;
  validUntil: string;
}

export interface SubmitResult {
  responseId: string;
  gift: GiftInfo;
}

export interface User {
  id: string;
  username: string;
  displayName: string;
  roles: string[];
  active: boolean;
}

export interface EventSummary {
  id: string;
  name: string;
  startsOn?: string;
  endsOn?: string;
  giftEnabled: boolean;
  active: boolean;
  version: number;
  publishedAt: string;
  publicUrl: string;
  responses?: number;
  giftsAwarded?: number;
  ackedSeq?: number;
  pending?: number;
  lastExportAt?: string;
  lastAckAt?: string;
  versions?: { version: number; publishedAt: string; checksum: string }[];
}

export interface Stats {
  eventId: string;
  eventName: string;
  giftEnabled: boolean;
  total: number;
  lastHour: number;
  today: number;
  giftsAwarded: number;
  recent: { responseId: string; visitor: string; city: string; submittedAt: string; awarded: boolean }[];
  at: string;
}

export interface GiftCard {
  responseId: string;
  eventId: string;
  eventName: string;
  giftEnabled: boolean;
  visitor: string;
  city: string;
  submittedAt: string;
  giftCode: string;
  awarded: boolean;
  awardedAt?: string;
  awardedBy?: string;
  result?: "ok" | "already" | "removed" | "not_changed";
}

// ---------- вызовы ----------

export const publicApi = {
  schema: (eventId: string) => api.get<unknown>(`/public/events/${eventId}`),
  call: (eventId: string, phone: string) => api.post<CallResult>(`/public/events/${eventId}/phone/call`, { phone }),
  verify: (eventId: string, phone: string, code: string) => api.post<VerifyResult>(`/public/events/${eventId}/phone/verify`, { phone, code }),
  submit: (eventId: string, token: string, answers: Record<string, unknown>, consent: boolean) =>
    api.post<SubmitResult>(`/public/events/${eventId}/responses`, { token, answers, consent }),
  gift: (eventId: string, token: string) => api.get<GiftInfo & { responseId: string }>(`/public/events/${eventId}/gift?token=${encodeURIComponent(token)}`),
};

export const authApi = {
  login: (username: string, password: string) => api.post<User>("/auth/login", { username, password }),
  me: () => api.get<User>("/auth/me"),
  logout: () => api.post<void>("/auth/logout"),
};

export const staffApi = {
  events: () => api.get<EventSummary[]>("/staff/events"),
  stats: (eventId: string) => api.get<Stats>(`/staff/events/${eventId}/stats`),
  lookup: (q: { token?: string; eventId?: string; code?: string }) => api.post<GiftCard>("/staff/gift/lookup", q),
  award: (q: { token?: string; eventId?: string; code?: string; awarded: boolean }) => api.post<GiftCard>("/staff/gift/award", q),
};

export const adminApi = {
  events: () => api.get<EventSummary[]>("/admin/events"),
  event: (id: string) => api.get<EventSummary>(`/admin/events/${id}`),
  questionnaire: (id: string, version?: number) => api.get<unknown>(`/admin/events/${id}/questionnaire${version ? `?version=${version}` : ""}`),
  setActive: (id: string, active: boolean) => api.patch<EventSummary>(`/admin/events/${id}`, { active }),
  responses: (id: string, page = 0, size = 50) =>
    api.get<{ total: number; page: number; size: number; items: Record<string, unknown>[] }>(`/admin/events/${id}/responses?page=${page}&size=${size}`),
  csvUrl: (id: string) => `${API_BASE}/admin/events/${id}/export.csv`,
  log: (eventId?: string, page = 0, size = 50) =>
    api.get<{ total: number; page: number; items: Record<string, unknown>[] }>(`/admin/log?page=${page}&size=${size}${eventId ? `&eventId=${eventId}` : ""}`),
  users: () => api.get<User[]>("/admin/users"),
  createUser: (u: { username: string; displayName: string; password: string; roles: string[] }) => api.post<User>("/admin/users", u),
  updateUser: (id: string, u: { displayName?: string; password?: string; roles?: string[]; active?: boolean }) => api.patch<User>(`/admin/users/${id}`, u),
};
