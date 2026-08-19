import { useEffect, useState, type FormEvent } from "react";
import { adminApi, ApiError, type User } from "../api/client";
import { Alert } from "../ui/Alert";
import { usePanel } from "../staff/StaffLayout";

const ROLES = [
  { id: "STAFF", label: "Персонал стенда" },
  { id: "ADMIN", label: "Администратор" },
  { id: "INTEGRATION", label: "Интеграция (1С)" },
];

export function AdminUsers() {
  const { user: me } = usePanel();
  const [users, setUsers] = useState<User[]>([]);
  const [error, setError] = useState("");
  const [ok, setOk] = useState("");
  const [form, setForm] = useState({ username: "", displayName: "", password: "", roles: ["STAFF"] as string[] });
  const [busy, setBusy] = useState(false);

  const load = () => adminApi.users().then(setUsers).catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка"));
  useEffect(() => {
    void load();
  }, []);

  const create = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    setOk("");
    try {
      await adminApi.createUser(form);
      setOk(`Пользователь ${form.username} создан.`);
      setForm({ username: "", displayName: "", password: "", roles: ["STAFF"] });
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось создать");
    } finally {
      setBusy(false);
    }
  };

  const toggleActive = async (u: User) => {
    try {
      await adminApi.updateUser(u.id, { active: !u.active });
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось изменить");
    }
  };

  const resetPassword = async (u: User) => {
    const password = prompt(`Новый пароль для ${u.username} (не короче 8 символов):`);
    if (!password) return;
    try {
      await adminApi.updateUser(u.id, { password });
      setOk(`Пароль ${u.username} изменён.`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось изменить пароль");
    }
  };

  const toggleRole = (role: string) =>
    setForm((f) => ({ ...f, roles: f.roles.includes(role) ? f.roles.filter((r) => r !== role) : [...f.roles, role] }));

  return (
    <div className="page page-wide">
      <h1>Пользователи</h1>
      {error && <Alert kind="error">{error}</Alert>}
      {ok && <Alert kind="ok">{ok}</Alert>}
      <div className="card table-wrap">
        <table>
          <thead><tr><th>Логин</th><th>Имя</th><th>Роли</th><th>Статус</th><th></th></tr></thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td>{u.username}{u.username === me.username && <span className="muted small"> (вы)</span>}</td>
                <td>{u.displayName}</td>
                <td className="small">{u.roles.join(", ")}</td>
                <td><span className={`badge ${u.active ? "ok" : "muted"}`}>{u.active ? "активен" : "отключён"}</span></td>
                <td className="row" style={{ gap: 6 }}>
                  <button type="button" className="sm secondary" onClick={() => resetPassword(u)}>Пароль</button>
                  {u.username !== me.username && (
                    <button type="button" className="sm secondary" onClick={() => toggleActive(u)}>{u.active ? "Отключить" : "Включить"}</button>
                  )}
                </td>
              </tr>
            ))}
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
        {ROLES.map((r) => (
          <label key={r.id} className={`opt${form.roles.includes(r.id) ? " on" : ""}`}>
            <input type="checkbox" checked={form.roles.includes(r.id)} onChange={() => toggleRole(r.id)} />
            <span>{r.label} <span className="muted small">({r.id})</span></span>
          </label>
        ))}
        <button className="mt" type="submit" disabled={busy || form.roles.length === 0}>{busy ? "Создаём…" : "Создать"}</button>
      </form>
    </div>
  );
}
