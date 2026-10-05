import { NavLink, Outlet, useOutletContext } from "react-router";
import { useEffect, useState } from "react";
import { ApiError, staffApi, type EventSummary, type User } from "../api/client";
import { GiftAwardLinks } from "./GiftAwardLinks";
import { LoginForm } from "../ui/LoginForm";
import { ThemeToggle } from "../ui/ThemeToggle";
import { useAuth } from "../ui/useAuth";

export interface PanelContext {
  user: User;
  logout: () => Promise<void>;
  events: EventSummary[] | null;
  eventId: string;
  setEventId: (id: string) => void;
  eventError: string;
}

export const usePanel = () => useOutletContext<PanelContext>();

/** Каркас панели стенда/админки: вход, шапка с навигацией, вложенные страницы. */
export function StaffLayout({ admin = false }: { admin?: boolean }) {
  const { user, loading, setUser, logout } = useAuth();

  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [eventId, setEventId] = useState(() => localStorage.getItem("survey.staff.event") ?? "");
  const [eventError, setEventError] = useState("");

  useEffect(() => {
    setEvents(null);
    setEventError("");
    if (!user) return;
    let alive = true;
    const refresh = () => staffApi.events().then((list) => {
      if (!alive) return;
      setEvents(list);
      setEventError("");
      setEventId((id) => list.some((e) => e.id === id) ? id : list[0]?.id ?? "");
    }).catch((e) => {
      if (alive) setEventError(e instanceof ApiError ? e.message : "Не удалось загрузить мероприятия.");
    });
    void refresh();
    const timer = window.setInterval(refresh, 10000);
    return () => { alive = false; window.clearInterval(timer); };
  }, [user?.id]);

  useEffect(() => {
    if (eventId) localStorage.setItem("survey.staff.event", eventId);
  }, [eventId]);

  if (loading) return <div className="status-box muted">Загрузка…</div>;

  if (!user) {
    return (
      <div className="page">
        <div className="row" style={{ justifyContent: "flex-end" }}><ThemeToggle /></div>
        <LoginForm onLogin={setUser} title={admin ? "Вход администратора" : "Вход для сотрудников стенда"} />
      </div>
    );
  }

  const isAdmin = user.roles.includes("ADMIN");
  /** супервайзер — «локальный админ» назначенных мероприятий: ответы, CSV, свои STAFF; без журнала */
  const isSupervisor = !isAdmin && user.roles.includes("SUPERVISOR");
  if (admin && !isAdmin && !isSupervisor) {
    return (
      <div className="page">
        <div className="card">
          <p>Для раздела администрирования нужна роль ADMIN или SUPERVISOR. Вы вошли как <b>{user.displayName}</b>.</p>
          <div className="row">
            <NavLink className="btn secondary" to="/staff">Панель стенда</NavLink>
            <button type="button" className="ghost" onClick={() => void logout()}>Выйти</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <>
      <header className="topbar">
        <nav>
          <NavLink to="/staff" end>Стенд</NavLink>
          <GiftAwardLinks event={events?.find((e) => e.id === eventId)} navigation />
          {(isAdmin || isSupervisor) && <NavLink to="/admin" end>Мероприятия</NavLink>}
          {isAdmin && <NavLink to="/admin/log">Журнал</NavLink>}
          {(isAdmin || isSupervisor) && <NavLink to="/admin/users">Пользователи</NavLink>}
        </nav>
        <div className="row" style={{ gap: 4 }}>
          <span className="muted small">{user.displayName}</span>
          <ThemeToggle />
          <button type="button" className="ghost sm" onClick={() => void logout()}>Выйти</button>
        </div>
      </header>
      <Outlet context={{ user, logout, events, eventId, setEventId, eventError } satisfies PanelContext} />
    </>
  );
}
