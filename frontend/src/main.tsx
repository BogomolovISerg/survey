import { StrictMode, useEffect } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Navigate, RouterProvider, useRouteError } from "react-router";
import { APP_BASE } from "./api/client";
import { initTheme } from "./lib/theme";
import { AdminEvent } from "./admin/AdminEvent";
import { AdminEvents } from "./admin/AdminEvents";
import { AdminLog } from "./admin/AdminLog";
import { AdminUsers } from "./admin/AdminUsers";
import { ManualGiftPage } from "./staff/ManualGiftPage";
import { GiftPage } from "./staff/GiftPage";
import { StaffHome } from "./staff/StaffHome";
import { StaffLayout } from "./staff/StaffLayout";
import { SurveyPage } from "./survey/SurveyPage";
import "./styles.css";

initTheme();

function Home() {
  return (
    <div className="page status-box">
      <h1>Анкетирование</h1>
      <p className="muted">Откройте анкету по QR-коду мероприятия.</p>
      <p><a href={`${APP_BASE}/staff`}>Панель стенда</a></p>
    </div>
  );
}

const RELOAD_MARK = "survey.chunk.reload";

/**
 * Экран ошибки маршрута. Частый случай — «Importing a module script failed»:
 * браузер держит index.html прошлой сборки, а её чанков на сервере уже нет после деплоя.
 * Тогда одна автоматическая перезагрузка (не чаще раза в минуту) подтягивает свежую сборку.
 */
function AppError() {
  const error = useRouteError();
  const message = error instanceof Error ? error.message : String(error);
  const staleChunk = /module script|dynamically imported module|Loading chunk/i.test(message);

  useEffect(() => {
    if (!staleChunk) return;
    const last = Number(window.sessionStorage.getItem(RELOAD_MARK) ?? 0);
    if (Date.now() - last < 60_000) return; // защита от цикла, если index.html закэширован намертво
    window.sessionStorage.setItem(RELOAD_MARK, String(Date.now()));
    window.location.reload();
  }, [staleChunk]);

  return (
    <div className="page status-box">
      <h1>Приложение обновилось</h1>
      <p className="muted">
        {staleChunk
          ? "Загружена устаревшая версия страницы. Обновите её, чтобы продолжить."
          : "Что-то пошло не так. Обновите страницу — и попробуйте ещё раз."}
      </p>
      <button type="button" onClick={() => window.location.reload()}>Обновить страницу</button>
    </div>
  );
}

const router = createBrowserRouter(
  [
    { path: "/", element: <Home />, errorElement: <AppError /> },
    { path: "/e/:eventId", element: <SurveyPage />, errorElement: <AppError /> },
    { path: "/login", element: <Navigate to="/staff" replace /> },
    {
      path: "/staff",
      element: <StaffLayout />,
      errorElement: <AppError />,
      children: [
        { index: true, element: <StaffHome /> },
        { path: "gift", element: <GiftPage /> },
        { path: "gift/manual", element: <ManualGiftPage /> },
      ],
    },
    {
      path: "/admin",
      element: <StaffLayout admin />,
      errorElement: <AppError />,
      children: [
        { index: true, element: <AdminEvents /> },
        { path: "events/:eventId", element: <AdminEvent /> },
        { path: "log", element: <AdminLog /> },
        { path: "users", element: <AdminUsers /> },
      ],
    },
    { path: "*", element: <div className="page status-box"><p>Страница не найдена.</p></div> },
  ],
  { basename: APP_BASE || "/" },
);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
