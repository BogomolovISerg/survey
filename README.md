# survey — анкетирование посетителей выставок (v2)

Отдельный веб-сервис вместо HTTP-сервиса 1С + Vue (v1). Мастер мероприятий и анкет — 1С:ERP (справочники
`бигМероприятия`, `бигКомпонентыАнкеты`), сервис исполняет анкету, подтверждает телефон flash-call'ом,
собирает ответы, ведёт панель стенда (выдача подарков по QR) и отдаёт результаты в 1С по запросу.
1С — всегда инициатор обмена. Проектная записка: `../Анкетирование_v2_проектная_записка.docx`.

Стек: Java 21 · Spring Boot 4.0 · PostgreSQL · React 19 + TypeScript + Vite · WAR во внешнем Tomcat 11 за nginx.

## Структура

```
pom.xml                          сборка WAR (фронт собирается frontend-maven-plugin в WEB-INF/classes/static)
src/main/java/ru/big/survey/
  config/    SurveyProperties (survey.* в yml), SecurityConfig (две цепочки: /api/v1/sync — Basic; остальное — сессия)
  domain/    Event, Questionnaire (версии схемы), Response, GiftAward, PhoneVerification, AppUser, SyncState, SyncLog
  persistence/ Spring Data репозитории
  service/   SyncService (publish/export/ack/status), ResponseService (схема, приём ответа, подарок посетителя),
             PhoneVerificationService + Zvonok/Stub FlashCallClient, TokenService (HMAC-токены), SchemaService,
             Conditions (интерпретатор условий v1), GiftService (стенд), AdminService, UserService, AuditService
  api/       PublicController, SyncController, StaffController, AdminController, AuthController, SpaController
src/main/resources/application.yml, db/migration/V1__survey_schema.sql
frontend/  React-приложение: /e/{guid} анкета, /staff панель стенда, /admin админка
deploy/    tomcat/application.example.yml, nginx/*.conf, db/create_database.sql
onec/      изменения в ERPServer1 (модуль обмена, реквизиты, форма, регламент) — описание и исходники
```

## API (кратко)

| Путь | Кто | Назначение |
|---|---|---|
| `GET /api/v1/public/events/{guid}` | посетитель | схема анкеты (формат v1) + `event` |
| `POST …/{guid}/phone/call` `{phone}` | посетитель | flash-call; `already_verified` → сразу токен |
| `POST …/{guid}/phone/verify` `{phone, code}` | посетитель | → `{token}` (HMAC, 30 мин) |
| `POST …/{guid}/responses` `{token, answers, consent}` | посетитель | ответ; 409 `already_submitted` с данными подарка |
| `GET …/{guid}/gift?token=` | посетитель | статус подарка, `giftToken/giftCode/giftUrl` для QR |
| `POST /api/v1/auth/login|logout`, `GET /api/v1/auth/me` | персонал | сессия в cookie |
| `GET /api/v1/staff/events`, `GET …/{guid}/stats` | STAFF/ADMIN | мероприятия, живые счётчики |
| `POST /api/v1/staff/gift/lookup|award` `{token}` или `{eventId, code}` | STAFF/ADMIN | карточка посетителя (без телефона), выдача/снятие |
| `GET/PATCH /api/v1/admin/events…`, `…/responses`, `…/export.csv`, `/admin/log`, `/admin/users` | ADMIN | админка |
| `PUT /api/v1/sync/events/{guid}` | INTEGRATION (Basic) | публикация мероприятия + анкеты (идемпотентно по checksum) |
| `GET …/{guid}/responses?after=&limit=` | INTEGRATION | ответы с `change_seq > after` (изменение подарка приходит повторно) |
| `POST …/{guid}/responses/ack` `{seq}` | INTEGRATION | подтверждение курсора |
| `GET …/{guid}/status` | INTEGRATION | счётчики |

Ошибки — JSON `{"error": code, "message": "…"}`.

## Сборка

Нужны JDK 21 и Maven 3.9+. Node ставится плагином сам (v24) — интернет для `npm ci` нужен на первой сборке.

```bash
mvn -DskipTests package          # → target/survey.war (фронт собирается автоматически)
mvn test                         # юнит-тесты backend
cd frontend && npm test          # vitest: conditions/schema/phone
```

`-Dskip.frontend=true` — собрать WAR без пересборки фронта (нужен уже собранный `target/generated-resources/frontend`).

## Локальный запуск для отладки

```bash
# PostgreSQL: create role survey_api login password 'survey_api'; create database survey owner survey_api;
export SURVEY_DB_URL=jdbc:postgresql://localhost:5432/survey SURVEY_DB_USERNAME=survey_api SURVEY_DB_PASSWORD=survey_api
export SURVEY_ADMIN_PASSWORD=admin12345 SURVEY_TOKEN_SECRET=dev-secret-dev-secret-dev-secret-32
export SURVEY_FLASHCALL_PROVIDER=stub SURVEY_PUBLIC_BASE_URL=http://localhost:8080
mvn -Dskip.frontend=true spring-boot:run     # backend на :8080 (фронт — из собранного ранее target/generated-resources)
cd frontend && npm run dev                   # фронт на :5173 с прокси /api → :8080 (горячая перезагрузка)
```

`stub` — код подтверждения всегда `1234` (пишется в лог), звонков нет. Никогда не включать на бою.

## Развёртывание на сервере (Tomcat 11 + PostgreSQL + nginx)

1. **БД:** `psql -U postgres -f deploy/db/create_database.sql` (заменить пароль).
2. **Конфиг:** `deploy/tomcat/application.example.yml` → `${catalina.base}/conf/apps/survey/application.yml`,
   заполнить пароли, `token-secret`, `public-base-url`, ключи zvonok.com; `chmod 600`, владелец — учётка Tomcat.
3. **WAR:** `cp target/survey.war ${catalina.base}/webapps/survey.war` — контекст `/survey`; Flyway создаст схему при старте.
   Лог: `${catalina.base}/logs/catalina.out` (строка `Started SurveyApplication`).
4. **nginx:** `deploy/nginx/survey-proxy.conf` → `/etc/nginx/snippets/`, `deploy/nginx/survey.conf` → `sites-enabled`
   (уточнить пути к сертификату и подсети ERP для `/api/v1/sync/`), `nginx -t && systemctl reload nginx`.
5. **DNS/NAT:** `survey.bigcom.ru` → внешний адрес, проброс 443 на nginx. ERP ходит к сервису по внутренней сети
   (тот же адрес через split-DNS/hosts либо внутренний адрес nginx).
6. **Первый вход:** `https://survey.bigcom.ru/survey/admin` — логин `admin` и пароль из `bootstrap-admin` (создаётся, только
   пока реестр пуст). Создать пользователей: `erp` (роль INTEGRATION — для 1С), промоутеров (STAFF).
7. **1С:** `бигАнкетированиеОбменСервер.УстановитьНастройкиОбмена("https://survey.bigcom.ru/survey", "erp", "пароль")`,
   далее кнопки в карточке мероприятия (см. `onec/README.md`).

Обновление версии: собрать новый WAR, заменить `webapps/survey.war` (Tomcat передеплоит), миграции применятся сами.

## Проверка после развёртывания (smoke)

```bash
B=https://survey.bigcom.ru/survey
curl -s $B/actuator/health                                    # с сервера: {"status":"UP"}; снаружи — 403 (nginx)
curl -s -o /dev/null -w "%{http_code}\n" $B/api/v1/sync/events/x/status   # снаружи 403, изнутри без пароля 401
curl -s -u erp:PASS -X PUT -H 'Content-Type: application/json' -d @deploy/publish-example.json $B/api/v1/sync/events/062c3656-b63e-11f0-811c-ac1f6b05c92a
```
Дальше — открыть `publicUrl` с телефона вне сети компании, пройти анкету, отсканировать QR подарка камерой телефона промоутера
(вход в `/survey/staff`), нажать «Загрузить результаты» в 1С.

## Безопасность (что настроено)

- `/api/v1/sync/**` — Basic + роль INTEGRATION, без сессии; в nginx закрыт снаружи.
- Сессии STAFF/ADMIN — cookie `HttpOnly; SameSite=Lax; Secure` (за TLS), 12 ч; CSRF отключён осознанно (JSON-API, SameSite).
- Токены верификации телефона и QR подарка — HMAC-SHA256 (усечённый до 128 бит), без состояния, с истечением.
- Код flash-call хранится хешем SHA-256(phone:code); TTL 10 мин, 5 попыток, повтор через 60 с, ≤8 звонков/номер/сутки.
- Телефоны в логах маскируются; персоналу стенда телефон/e-mail не отдаются; actuator наружу закрыт.
- Пароли — bcrypt; первый администратор — из конфига только при пустом реестре.

## Обмен с 1С — контракт

Публикация: `PUT /api/v1/sync/events/{guid}` с телом
`{name, startsOn, endsOn, giftEnabled, active, theme?, questionnaire: {enums, components, schema, style, output}}` —
`questionnaire` = результат `Справочники.бигКомпонентыАнкеты.СформироватьJSON` без изменений. Одинаковая схема новую версию не создаёт.

Выгрузка: `GET …/responses?after=<курсор>&limit=200` → `{items:[{seq,id,phone,submittedAt,questionnaireVersion,consentAt,answers,gift:{awarded,awardedAt,by}}], nextAfter, hasMore, ackedSeq}`.
1С пишет каждую строку в РС `бигДанныеАнкет` (`Дата`=submittedAt, `Идентификатор`=id, `Данные`=answers, `БонусПредоставлен`=gift.awarded),
сохраняет `nextAfter` в `КурсорВыгрузки` и шлёт `POST …/responses/ack {seq}`. Изменение подарка после выгрузки приходит повторно
с новым `seq` — та же строка регистра обновляется.
