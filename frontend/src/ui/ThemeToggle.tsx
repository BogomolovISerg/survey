import { useState } from "react";
import { currentTheme, toggleTheme } from "../lib/theme";

export function ThemeToggle() {
  const [theme, setTheme] = useState(() => (typeof document !== "undefined" ? currentTheme() : "light"));
  return (
    <button type="button" className="theme-toggle" title="Светлая/тёмная тема" aria-label="Переключить тему" onClick={() => setTheme(toggleTheme())}>
      {theme === "dark" ? "☀️" : "🌙"}
    </button>
  );
}
