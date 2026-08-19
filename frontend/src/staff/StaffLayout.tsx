import { NavLink, Outlet, useOutletContext } from "react-router";
import type { User } from "../api/client";
import { LoginForm } from "../ui/LoginForm";
import { ThemeToggle } from "../ui/ThemeToggle";
import { useAuth } from "../ui/useAuth";

export interface PanelContext {
  user: User;
  logout: () => Promise<void>;
}

export const usePanel = () => useOutletContext<PanelContext>();

/** Каркас панели стенда/админки: вход, шапка с навигацией, вложенные страницы. */
export function StaffLayout({ admin = false }: { admin?: boolean }) {
  const { user, loading, setUser, logout } = useAuth();

  if (loading) return <div className="status-box muted">Загрузка…</div>;

  if (!user) {
    return (
      <div className="page">
        <div className="row" style={{ justifyContent: "flex-end" }}><ThemeToggle /></div>
        <LoginForm onLogin={setUser} title={admin ? "Вход администратора" : "Вход для персонала стенда"} />
      </div>
    );
  }

  const isAdmin = user.roles.includes("ADMIN");
  if (admin && !isAdmin) {
    return (
      <div className="page">
        <div className="card">
          <p>Для раздела администрирования нужна роль ADMIN. Вы вошли как <b>{user.displayName}</b>.</p>
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
          <NavLink to="/staff/gift">Подарок</NavLink>
          {isAdmin && <NavLink to="/admin" end>Мероприятия</NavLink>}
          {isAdmin && <NavLink to="/admin/log">Журнал</NavLink>}
          {isAdmin && <NavLink to="/admin/users">Пользователи</NavLink>}
        </nav>
        <div className="row" style={{ gap: 4 }}>
          <span className="muted small">{user.displayName}</span>
          <ThemeToggle />
          <button type="button" className="ghost sm" onClick={() => void logout()}>Выйти</button>
        </div>
      </header>
      <Outlet context={{ user, logout } satisfies PanelContext} />
    </>
  );
}
