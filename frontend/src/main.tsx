import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Navigate, RouterProvider } from "react-router";
import { APP_BASE } from "./api/client";
import { initTheme } from "./lib/theme";
import { AdminEvent } from "./admin/AdminEvent";
import { AdminEvents } from "./admin/AdminEvents";
import { AdminLog } from "./admin/AdminLog";
import { AdminUsers } from "./admin/AdminUsers";
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

const router = createBrowserRouter(
  [
    { path: "/", element: <Home /> },
    { path: "/e/:eventId", element: <SurveyPage /> },
    { path: "/login", element: <Navigate to="/staff" replace /> },
    {
      path: "/staff",
      element: <StaffLayout />,
      children: [
        { index: true, element: <StaffHome /> },
        { path: "gift", element: <GiftPage /> },
      ],
    },
    {
      path: "/admin",
      element: <StaffLayout admin />,
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
