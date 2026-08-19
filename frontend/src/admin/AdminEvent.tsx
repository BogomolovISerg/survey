import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { adminApi, ApiError, type EventSummary } from "../api/client";
import { fmtDateTime } from "../lib/format";
import { Alert } from "../ui/Alert";
import { QrCode } from "../ui/QrCode";

type Row = Record<string, unknown> & { answers?: Record<string, unknown> };

export function AdminEvent() {
  const { eventId = "" } = useParams();
  const [event, setEvent] = useState<EventSummary | null>(null);
  const [rows, setRows] = useState<Row[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [error, setError] = useState("");
  const [showQr, setShowQr] = useState(false);
  const size = 50;

  useEffect(() => {
    adminApi.event(eventId).then(setEvent).catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка"));
  }, [eventId]);
  useEffect(() => {
    adminApi
      .responses(eventId, page, size)
      .then((r) => {
        setRows(r.items as Row[]);
        setTotal(r.total);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка"));
  }, [eventId, page]);

  const columns = Array.from(new Set(rows.flatMap((r) => Object.keys(r.answers ?? {}))));

  return (
    <div className="page page-wide">
      <p><Link to="/admin">← Мероприятия</Link></p>
      {error && <Alert kind="error">{error}</Alert>}
      {event && (
        <div className="card">
          <h1>{event.name}</h1>
          <div className="row small muted">
            <span>Версия анкеты {event.version} от {fmtDateTime(event.publishedAt)}</span>
            <span>·</span>
            <span>{event.active ? "открыта" : "закрыта"}</span>
            <span>·</span>
            <span>{event.giftEnabled ? "с подарками" : "без подарков"}</span>
          </div>
          <div className="row mt">
            <a className="btn secondary sm" href={event.publicUrl} target="_blank" rel="noreferrer">Открыть анкету</a>
            <button type="button" className="secondary sm" onClick={() => setShowQr((s) => !s)}>{showQr ? "Скрыть QR" : "QR-код анкеты"}</button>
            <a className="btn secondary sm" href={adminApi.csvUrl(event.id)}>Скачать CSV</a>
          </div>
          {showQr && <QrCode value={event.publicUrl} label="QR-код анкеты" />}
          <p className="small mt muted">Ссылка: {event.publicUrl}</p>
          <div className="row small">
            <span>Анкет: <b>{event.responses}</b></span>
            {event.giftEnabled && <span>Подарков выдано: <b>{event.giftsAwarded}</b></span>}
            <span>Не выгружено в 1С: <b>{event.pending}</b> (курсор {event.ackedSeq})</span>
            {event.lastExportAt && <span>Последняя выгрузка {fmtDateTime(event.lastExportAt)}</span>}
          </div>
          {event.versions && event.versions.length > 1 && (
            <p className="small muted mt">Версии: {event.versions.map((v) => `${v.version} (${fmtDateTime(v.publishedAt)})`).join(", ")}</p>
          )}
        </div>
      )}
      <div className="card table-wrap">
        <h3>Ответы ({total})</h3>
        <table>
          <thead>
            <tr>
              <th>Дата</th><th>Телефон</th>{event?.giftEnabled && <th>Подарок</th>}{columns.map((c) => <th key={c}>{c}</th>)}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={String(r.id)}>
                <td className="small">{fmtDateTime(String(r.submittedAt))}<div className="muted">v{String(r.version)}</div></td>
                <td>{String(r.phone)}</td>
                {event?.giftEnabled && (
                  <td>{r.giftAwarded ? <span className="badge ok">выдан</span> : <span className="badge muted">нет</span>}<div className="muted small">код {String(r.giftCode ?? "")}</div></td>
                )}
                {columns.map((c) => <td key={c}>{String(r.answers?.[c] ?? "")}</td>)}
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
