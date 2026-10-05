import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router";
import { adminApi, ApiError, type EventSummary, type PhoneSearchResult } from "../api/client";
import { fmtDate, fmtDateTime, fmtTime } from "../lib/format";
import { Alert } from "../ui/Alert";
import { usePanel } from "../staff/StaffLayout";

const shortCode = (c: string) => (c.length > 28 ? `${c.slice(0, 12)}…${c.slice(-6)}` : c);

export function AdminEvents() {
  const { user: me } = usePanel();
  const isAdmin = me.roles.includes("ADMIN");
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [error, setError] = useState("");
  /** поиск анкет по телефону — только ADMIN */
  const [phoneQuery, setPhoneQuery] = useState("");
  const [search, setSearch] = useState<PhoneSearchResult | null>(null);
  const [searching, setSearching] = useState(false);

  const load = () =>
    adminApi
      .events()
      .then(setEvents)
      .catch((e) => setError(e instanceof ApiError ? e.message : "Ошибка загрузки"));
  useEffect(() => {
    void load();
  }, []);

  const toggle = async (e: EventSummary) => {
    if (!confirm(e.active ? `Закрыть анкету «${e.name}»? Посетители увидят «мероприятие завершено».` : `Открыть анкету «${e.name}»?`)) return;
    try {
      await adminApi.setActive(e.id, !e.active);
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось изменить");
    }
  };

  const runSearch = async () => {
    setSearching(true);
    setError("");
    try {
      setSearch(await adminApi.searchResponses(phoneQuery));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось выполнить поиск");
    } finally {
      setSearching(false);
    }
  };

  const doSearch = async (e: FormEvent) => {
    e.preventDefault();
    if (phoneQuery.replace(/\D/g, "").length < 4) return;
    await runSearch();
  };

  /** Спорное снятие отметки выдачи прямо из результатов поиска: история и КМ сохраняются, стенд сможет выдать повторно. */
  const unaward = async (id: string) => {
    if (!window.confirm("Снять отметку «подарок выдан»? История выдачи и коды маркировки сохранятся, стенд сможет выдать подарок повторно.")) return;
    setSearching(true);
    setError("");
    try {
      await adminApi.unaward(id);
      await runSearch();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Не удалось снять отметку");
      setSearching(false);
    }
  };

  return (
    <div className="page page-wide">
      <h1>Мероприятия</h1>
      {error && <Alert kind="error">{error}</Alert>}
      {isAdmin && (
        <form className="card" onSubmit={doSearch}>
          <h3>Поиск анкеты по телефону</h3>
          <div className="row">
            <input type="tel" className="grow" placeholder="+7 900 000-00-00 или последние цифры (от 4)"
              value={phoneQuery} onChange={(e) => setPhoneQuery(e.target.value)} />
            <button type="submit" className="secondary" disabled={searching || phoneQuery.replace(/\D/g, "").length < 4}>
              {searching ? "Ищем…" : "Найти"}
            </button>
            {search && <button type="button" className="ghost" onClick={() => { setSearch(null); setPhoneQuery(""); }}>Очистить</button>}
          </div>
          {search?.error && <p className="muted small mt">{search.error}</p>}
          {search && !search.error && search.items.length === 0 && <p className="muted small mt">Анкеты с таким номером не найдены.</p>}
          {search && search.items.length > 0 && (
            <div className="table-wrap mt">
              <table>
                <thead>
                  <tr><th>Посетитель</th><th>Телефон</th><th>Мероприятие</th><th>Анкета</th><th>Подарок</th></tr>
                </thead>
                <tbody>
                  {search.items.map((r) => (
                    <tr key={r.id}>
                      <td>{r.visitor}<div className="muted small">{r.city}</div></td>
                      <td>{r.phone}</td>
                      <td><Link to={`/admin/events/${r.eventId}`}>{r.eventName}</Link></td>
                      <td className="small">{fmtDateTime(r.submittedAt)}<div className="muted">код {r.giftCode}</div></td>
                      <td>
                        {r.giftAwarded ? <span className="badge ok">выдан</span> : <span className="badge muted">нет</span>}
                        {r.giftAwardedAt && (
                          <div className="muted small">{fmtTime(r.giftAwardedAt)}{r.giftAwardedBy ? ` · ${r.giftAwardedBy}` : ""}{!r.giftAwarded && " (снято)"}</div>
                        )}
                        {r.giftItemCodes.length > 0 && <div className="muted small">КМ: {r.giftItemCodes.map(shortCode).join(", ")}</div>}
                        {r.giftAwarded && (
                          <div>
                            <button type="button" className="ghost sm" disabled={searching} onClick={() => unaward(r.id)}>
                              снять отметку (спор)
                            </button>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {(search.total ?? 0) >= 50 && <p className="muted small mt">Показаны первые 50 — уточните номер.</p>}
            </div>
          )}
        </form>
      )}
      {events && events.length === 0 && <Alert kind="info">Пока ничего не опубликовано. Публикация выполняется из 1С (кнопка «Опубликовать» в карточке мероприятия).</Alert>}
      {events && events.length > 0 && (
        <div className="card table-wrap">
          <table>
            <thead>
              <tr>
                <th>Мероприятие</th><th>Даты</th><th>Версия</th><th>Анкет</th><th>Подарков</th><th>Не выгружено в 1С</th><th>Статус</th><th></th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.id}>
                  <td><Link to={`/admin/events/${e.id}`}>{e.name}</Link><div className="muted small">{e.id}</div></td>
                  <td className="small">{fmtDate(e.startsOn)}{e.endsOn ? ` — ${fmtDate(e.endsOn)}` : ""}</td>
                  <td>{e.version}<div className="muted small">{fmtDateTime(e.publishedAt)}</div></td>
                  <td>{e.responses}</td>
                  <td>{e.giftEnabled ? e.giftsAwarded : "—"}</td>
                  <td>{e.pending}{e.lastAckAt ? <div className="muted small">ack {fmtDateTime(e.lastAckAt)}</div> : null}</td>
                  <td><span className={`badge ${e.active ? "ok" : "muted"}`}>{e.active ? "открыта" : "закрыта"}</span></td>
                  <td><button type="button" className="sm secondary" onClick={() => toggle(e)}>{e.active ? "Закрыть" : "Открыть"}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
