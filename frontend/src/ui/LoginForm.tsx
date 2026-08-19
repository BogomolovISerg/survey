import { useState, type FormEvent } from "react";
import { ApiError, authApi, type User } from "../api/client";
import { Alert } from "./Alert";

/** Форма входа персонала/администратора. */
export function LoginForm({ onLogin, title = "Вход" }: { onLogin: (u: User) => void; title?: string }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      onLogin(await authApi.login(username, password));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось войти.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="card" onSubmit={submit}>
      <h2>{title}</h2>
      <label className="lbl" htmlFor="login-user">Логин</label>
      <input id="login-user" type="text" autoComplete="username" autoCapitalize="none" value={username} onChange={(e) => setUsername(e.target.value)} required />
      <label className="lbl" htmlFor="login-pass">Пароль</label>
      <input id="login-pass" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
      {error && <Alert kind="error">{error}</Alert>}
      <button className="block mt" type="submit" disabled={busy}>{busy ? "Входим…" : "Войти"}</button>
    </form>
  );
}
