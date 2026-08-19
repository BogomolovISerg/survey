import { useEffect, useState } from "react";
import { adminApi, ApiError } from "../api/client";
import { fmtDateTime } from "../lib/format";
import { Alert } from "../ui/Alert";

export function AdminLog() {
  const [items, setItems] = useState<Record<string, unknown>[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [error, setError] = useState("");
  const size = 50;

  useEffect(() => {
    adminApi
      .log(undefined, page, size)
      .then((r) => {
        setItems(r.items);
        setTotal(r.total);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка"));
  }, [page]);

  return (
    <div className="page page-wide">
      <h1>Журнал</h1>
      {error && <Alert kind="error">{error}</Alert>}
      <div className="card table-wrap">
        <table>
          <thead><tr><th>Время</th><th>Тип</th><th>Статус</th><th>Кто</th><th>Мероприятие</th><th>Детали</th></tr></thead>
          <tbody>
            {items.map((i) => (
              <tr key={String(i.id)}>
                <td className="small">{fmtDateTime(String(i.at))}</td>
                <td>{String(i.kind)}</td>
                <td><span className={`badge ${i.status === "OK" ? "ok" : "warn"}`}>{String(i.status)}</span></td>
                <td>{String(i.actor)}</td>
                <td className="small muted">{i.eventId ? String(i.eventId).slice(0, 8) : ""}</td>
                <td className="small"><code>{JSON.stringify(i.details ?? {})}</code></td>
              </tr>
            ))}
          </tbody>
        </table>
        {total > size && (
          <div className="row mt">
            <button type="button" className="secondary sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>← Назад</button>
            <span className="small muted">стр. {page + 1} из {Math.ceil(total / size)}</span>
            <button type="button" className="secondary sm" disabled={(page + 1) * size >= total} onClick={() => setPage((p) => p + 1)}>Вперёд →</button>
          </div>
        )}
      </div>
    </div>
  );
}
