import { useCallback, useEffect, useState } from "react";
import { authApi, type User } from "../api/client";

/** Текущий пользователь сессии: loading → user | null. */
export function useAuth() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let alive = true;
    authApi.me().then((u) => alive && setUser(u)).catch(() => alive && setUser(null)).finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, []);
  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      setUser(null);
    }
  }, []);
  return { user, loading, setUser, logout };
}
