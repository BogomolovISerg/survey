import { useEffect, useState, type FormEvent } from "react";
import { adminApi, ApiError, type EventSummary, type User } from "../api/client";
import { Alert } from "../ui/Alert";
import { usePanel } from "../staff/StaffLayout";

const ROLES = [
  { id: "STAFF", label: "Персонал стенда" },
  { id: "SUPERVISOR", label: "Супервайзер (админ мероприятия)" },
  { id: "ADMIN", label: "Администратор" },
  { id: "INTEGRATION", label: "Интеграция (1С)" },
];

/** Мероприятия, к которым имеет смысл привязывать персонал: открытые. */
const assignableEvents = (events: EventSummary[]) => events.filter((e) => e.active);

export function AdminUsers() {
  const { user: me } = usePanel();
  /** супервайзер управляет только STAFF своих мероприятий; роли менять не может */
  const supervisorMode = !me.roles.includes("ADMIN");
  const [users, setUsers] = useState<User[]>([]);
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [error, setError] = useState("");
  const [ok, setOk] = useState("");
  const [form, setForm] = useState({ username: "", displayName: "", password: "", roles: ["STAFF"] as string[], eventIds: [] as string[] });
  const [busy, setBusy] = useState(false);
  /** id пользователя, чья карточка раскрыта */
  const [openId, setOpenId] = useState<string | null>(null);
  const [edit, setEdit] = useState<{ displayName: string; roles: string[]; eventIds: string[]; blockRejectedMarks: boolean } | null>(null);
  const [newPassword, setNewPassword] = useState("");

  const eventName = (id: string) => events.find((e) => e.id === id)?.name ?? id.slice(0, 8);

  const load = () =>
    Promise.all([adminApi.users(), adminApi.events()])
      .then(([u, e]) => {
        setUsers(u);
        setEvents(e);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  useEffect(() => {
    void load();
  }, []);

  const flash = (msg: string) => {
    setOk(msg);
    setError("");
    window.setTimeout(() => setOk(""), 4000);
  };
  const fail = (e: unknown, fallback: string) => setError(e instanceof ApiError ? e.message : fallback);

  // ---------- создание ----------
  const create = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await adminApi.createUser(form);
      flash(`Пользователь ${form.username} создан.`);
      setForm({ username: "", displayName: "", password: "", roles: ["STAFF"], eventIds: [] });
      await load();
    } catch (err) {
      fail(err, "Не удалось создать");
    } finally {
      setBusy(false);
    }
  };

  // ---------- карточка ----------
  const openCard = (u: User) => {
    setOpenId(u.id);
    setEdit({ displayName: u.displayName, roles: [...u.roles], eventIds: [...(u.events ?? [])], blockRejectedMarks: u.blockRejectedMarks ?? true });
    setNewPassword("");
    setError("");
  };

  const saveCard = async (u: User) => {
    if (!edit) return;
    setBusy(true);
    setError("");
    try {
      await adminApi.updateUser(u.id, { displayName: edit.displayName, roles: edit.roles, eventIds: edit.eventIds, blockRejectedMarks: edit.blockRejectedMarks });
      flash(`Пользователь ${u.username} сохранён.`);
      await load();
    } catch (err) {
      fail(err, "Не удалось сохранить");
    } finally {
      setBusy(false);
    }
  };

  const changePassword = async (u: User) => {
    if (newPassword.length < 8) {
      setError("Пароль не короче 8 символов.");
      return;
    }
    setBusy(true);
    setError("");
    try {
      await adminApi.updateUser(u.id, { password: newPassword });
      setNewPassword("");
      flash(`Пароль пользователя ${u.username} изменён.`);
    } catch (err) {
      fail(err, "Не удалось изменить пароль");
    } finally {
      setBusy(false);
    }
  };

  const toggleActive = async (u: User) => {
    setBusy(true);
    setError("");
    try {
      await adminApi.updateUser(u.id, { active: !u.active });
      flash(u.active ? `Пользователь ${u.username} отключён.` : `Пользователь ${u.username} включён.`);
      await load();
    } catch (err) {
      fail(err, "Не удалось изменить");
    } finally {
      setBusy(false);
    }
  };

  const toggleIn = (list: string[], value: string) => (list.includes(value) ? list.filter((x) => x !== value) : [...list, value]);

  const eventChecks = (selected: string[], onChange: (next: string[]) => void) => {
    const open = assignableEvents(events);
    if (open.length === 0) return <p className="muted small">Открытых мероприятий нет — опубликуйте мероприятие из 1С.</p>;
    return open.map((e) => (
      <label key={e.id} className={`opt${selected.includes(e.id) ? " on" : ""}`}>
        <input type="checkbox" checked={selected.includes(e.id)} onChange={() => onChange(toggleIn(selected, e.id))} />
        <span>{e.name}</span>
      </label>
    ));
  };

  return (
    <div className="page page-wide">
      <h1>Пользователи</h1>
      {error && <Alert kind="error">{error}</Alert>}
      {ok && <Alert kind="ok">{ok}</Alert>}

      <div className="card table-wrap">
        <table>
          <thead><tr><th>Логин</th><th>Имя</th><th>Роли</th><th>Мероприятия</th><th>Статус</th><th></th></tr></thead>
          <tbody>
            {users.map((u) => [
              <tr key={u.id}>
                <td>{u.username}{u.username === me.username && <span className="muted small"> (вы)</span>}</td>
                <td>{u.displayName}</td>
                <td className="small">{u.roles.join(", ")}</td>
                <td className="small">
                  {u.roles.includes("ADMIN")
                    ? <span className="muted">все (ADMIN)</span>
                    : u.roles.includes("STAFF") || u.roles.includes("SUPERVISOR")
                      ? (u.events ?? []).length > 0
                        ? (u.events ?? []).map(eventName).join(", ")
                        : <span className="badge warn">не назначено</span>
                      : "—"}
                </td>
                <td><span className={`badge ${u.active ? "ok" : "muted"}`}>{u.active ? "активен" : "отключён"}</span></td>
                <td>
                  <button type="button" className="sm secondary" onClick={() => (openId === u.id ? setOpenId(null) : openCard(u))}>
                    {openId === u.id ? "Свернуть" : "Карточка"}
                  </button>
                </td>
              </tr>,
              openId === u.id && edit ? (
                <tr key={`${u.id}-card`}>
                  <td colSpan={6} style={{ background: "var(--bg-soft)" }}>
                    <div className="row" style={{ alignItems: "flex-start", gap: "1.5rem" }}>
                      <div style={{ minWidth: 260 }} className="grow">
                        <label className="lbl" htmlFor={`dn-${u.id}`}>Отображаемое имя</label>
                        <input id={`dn-${u.id}`} type="text" value={edit.displayName}
                          onChange={(e) => setEdit({ ...edit, displayName: e.target.value })} />
                        <label className="lbl">Роли</label>
                        {supervisorMode ? (
                          <p className="small" style={{ marginBottom: 0 }}>{edit.roles.join(", ")} <span className="muted">(роли меняет администратор)</span></p>
                        ) : (
                          ROLES.map((r) => (
                            <label key={r.id} className={`opt${edit.roles.includes(r.id) ? " on" : ""}`}>
                              <input type="checkbox" checked={edit.roles.includes(r.id)}
                                onChange={() => setEdit({ ...edit, roles: toggleIn(edit.roles, r.id) })} />
                              <span>{r.label} <span className="muted small">({r.id})</span></span>
                            </label>
                          ))
                        )}
                      </div>
                      <div style={{ minWidth: 280 }} className="grow">
                        <label className={`opt${edit.blockRejectedMarks ? " on" : ""}`}>
                          <input type="checkbox" checked={edit.blockRejectedMarks}
                            onChange={() => setEdit({ ...edit, blockRejectedMarks: !edit.blockRejectedMarks })} />
                          <span>Блокировать выдачу при КМ не в обороте <span className="muted small">(без галки — только предупреждение)</span></span>
                        </label>
                        <label className="lbl">Назначенные мероприятия</label>
                        {eventChecks(edit.eventIds, (next) => setEdit({ ...edit, eventIds: next }))}
                        <p className="muted small">Сотрудник видит на стенде только назначенные мероприятия.</p>
                      </div>
                      <div style={{ minWidth: 240 }} className="grow">
                        <label className="lbl" htmlFor={`pw-${u.id}`}>Новый пароль (не короче 8 символов)</label>
                        <input id={`pw-${u.id}`} type="text" autoComplete="new-password" value={newPassword}
                          onChange={(e) => setNewPassword(e.target.value)} />
                        <button type="button" className="sm secondary mt" disabled={busy || newPassword.length < 8}
                          onClick={() => changePassword(u)}>
                          Сменить пароль
                        </button>
                      </div>
                    </div>
                    <div className="row mt">
                      <button type="button" disabled={busy} onClick={() => saveCard(u)}>Сохранить</button>
                      {u.username !== me.username && (
                        <button type="button" className="secondary" disabled={busy} onClick={() => toggleActive(u)}>
                          {u.active ? "Отключить" : "Включить"}
                        </button>
                      )}
                      <button type="button" className="ghost" onClick={() => setOpenId(null)}>Закрыть</button>
                    </div>
                  </td>
                </tr>
              ) : null,
            ])}
          </tbody>
        </table>
      </div>

      <form className="card" onSubmit={create}>
        <h3>Новый пользователь</h3>
        <label className="lbl" htmlFor="nu">Логин</label>
        <input id="nu" type="text" autoCapitalize="none" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} required />
        <label className="lbl" htmlFor="nd">Отображаемое имя</label>
        <input id="nd" type="text" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
        <label className="lbl" htmlFor="np">Пароль (не короче 8 символов)</label>
        <input id="np" type="text" autoComplete="new-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required />
        <label className="lbl">Роли</label>
        {supervisorMode ? (
          <p className="small" style={{ marginBottom: 0 }}>Персонал стенда (STAFF)</p>
        ) : (
          ROLES.map((r) => (
            <label key={r.id} className={`opt${form.roles.includes(r.id) ? " on" : ""}`}>
              <input type="checkbox" checked={form.roles.includes(r.id)} onChange={() => setForm({ ...form, roles: toggleIn(form.roles, r.id) })} />
              <span>{r.label} <span className="muted small">({r.id})</span></span>
            </label>
          ))
        )}
        {(form.roles.includes("STAFF") || form.roles.includes("SUPERVISOR")) && (
          <>
            <label className="lbl">Назначить на мероприятия</label>
            {eventChecks(form.eventIds, (next) => setForm({ ...form, eventIds: next }))}
            {supervisorMode && form.eventIds.length === 0 && (
              <p className="muted small">Выберите хотя бы одно мероприятие.</p>
            )}
          </>
        )}
        <button className="mt" type="submit" disabled={busy || form.roles.length === 0 || (supervisorMode && form.eventIds.length === 0)}>
          {busy ? "Создаём…" : "Создать"}
        </button>
      </form>
    </div>
  );
}
