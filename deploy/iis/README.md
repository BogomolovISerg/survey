# Публикация survey наружу через IIS (erp-web) + ARR

Схема A (описана ниже), когда внешний адрес компании проброшен прямо на IIS (erp-web), а сервис живёт на 10.0.0.4.
Если у вас перед IIS стоит свой nginx (интернет → nginx → IIS → Tomcat), см. раздел «Схема B» ниже: там лимиты и
передача адреса посетителя устроены иначе.

```
Интернет ──443──▶ IIS erp-web (биндинг survey.bigcom.ru, wildcard *.bigcom.ru, ARR)
                      │ proxy http://10.0.0.4/…  (X-Forwarded-Proto=https, X-Forwarded-Host)
                      ▼
              10.0.0.4: nginx (:80, survey-locations.conf) ──▶ Tomcat :8080 /survey
```

## Шаги на erp-web (IIS)

1. DNS: `survey.bigcom.ru` → тот же внешний IP, что и 1cbig.bigcom.ru.
2. Установить **URL Rewrite** и **Application Request Routing 3.0** (msi с сайта IIS), перезапустить IIS Manager.
3. Узел сервера → **Application Request Routing Cache → Server Proxy Settings**:
   - Enable proxy = ✔
   - Reverse rewrite host in response headers = ✖ (снять!)
4. Узел сервера → **URL Rewrite → View Server Variables** → добавить `HTTP_X_FORWARDED_PROTO` и `HTTP_X_FORWARDED_HOST`.
   В том же окне Server Proxy Settings (шаг 3) убедиться, что **Include TCP port from client IP = ✖**: порт в адресе
   сломает разбор `X-Forwarded-For`.
5. Создать пустой каталог `C:\inetpub\survey-proxy`, положить туда `web.config` из этой папки.
6. Новый сайт **survey-proxy**: физический путь — этот каталог; биндинг **https, host name `survey.bigcom.ru`, порт 443,
   SNI = ✔, сертификат — wildcard `*.bigcom.ru`** (тот же, что на 1cbig). Пул приложений — любой (No Managed Code).
7. Проверка: `https://survey.bigcom.ru/survey/` открывается; `https://survey.bigcom.ru/survey/api/v1/sync/events/x/status` → 404.

## Ограничение частоты и защита бюджета на звонки

IIS не умеет считать лимит отдельно для одного пути (`…/phone/call`), поэтому лимит всегда ставится на nginx, который
видит настоящий адрес посетителя. Какой это nginx, зависит от схемы.

### Схема A: интернет → IIS → внутренний nginx (10.0.0.4) → Tomcat

1. В `web.config` снять комментарий с правила `HTTP_X_FORWARDED_FOR` и объявить эту переменную в URL Rewrite → View
   Server Variables **до** замены `web.config` (необъявленная переменная даёт 500). Правило перезаписывает
   `X-Forwarded-For` адресом, который видит IIS: подставленное клиентом значение отбрасывается.
2. На 10.0.0.4 в `survey-locations.conf` заменить `ERP_WEB_IP/32` (блок `geo`) на внутренний адрес erp-web. Лимиты
   считаются по последнему элементу `X-Forwarded-For`, но только для запросов от этого адреса. Пока адрес не подставлен,
   `nginx -t` сообщает об ошибке: так задумано, иначе лимит считался бы по адресу erp-web, то есть для всех сразу.
   Затем `nginx -t && systemctl reload nginx`.
3. Dynamic IP Restrictions на сайте `survey-proxy` (по желанию) защищают сам erp-web от потока запросов, но действуют на весь
   сайт, а не на путь; значения задавайте с большим запасом. На сайте, где работает 1С, не включать.

### Схема B: интернет → nginx → IIS → Tomcat (перед IIS стоит свой nginx)

Здесь именно краевой nginx видит настоящий адрес, IIS и Tomcat видят адрес nginx.

1. Лимиты ставятся на краевом nginx: зоны и `location` из `../nginx/survey-edge-ratelimit.conf` (proxy_pass и заголовки
   скопируйте из вашего существующего `location` для `/survey/`). `survey-locations.conf` в этой схеме не нужен.
2. **`HTTP_X_FORWARDED_FOR` в `web.config` не включать:** `{REMOTE_ADDR}` на IIS это адрес nginx, и настоящий адрес
   посетителя, который nginx передал в `X-Forwarded-For`, был бы потерян. IIS (ARR) должен передать заголовок дальше
   (обычно ARR дописывает адрес nginx в конец; это стоит проверить, см. проверку ниже). Tomcat
   (`forward-headers-strategy: native`) идёт по `X-Forwarded-For` справа налево, пропускает адреса внутренних прокси
   (по умолчанию частные диапазоны) и берёт первый внешний, поэтому подставленное клиентом значение ему не мешает.
   Если у nginx или IIS публичный адрес, добавьте его в `server.tomcat.remoteip.internal-proxies`, иначе Tomcat примет
   его за посетителя.
3. **Dynamic IP Restrictions на IIS не включать:** он видит только адрес nginx и либо ничего не ограничит, либо
   заблокирует сразу всех посетителей, а сайт общий с 1С.
4. Краевой nginx должен передавать `X-Forwarded-For $proxy_add_x_forwarded_for` (а не затирать его) и `Host`.
5. Правило «block sync from internet» из `web.config` должно действовать и на хосте, по которому анкета открывается
   снаружи (например `1cbig.bigcom.ru`).

Суточный предел звонков на весь сервис задаётся в приложении: `survey.verification.max-calls-per-day`
(см. `deploy/tomcat/application.example.yml`).

## Проверка после настройки

```bash
# 1. Закрытая интеграция: снаружи должно быть 404 (подставьте внешний хост анкеты)
curl -s -o /dev/null -w '%{http_code}\n' https://HOST/survey/api/v1/sync/events/x/status

# 2. Лимит срабатывает (звонки не заказываются: случайный GUID даёт 404 до обращения к провайдеру).
for i in $(seq 1 60); do curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  -H 'Content-Type: application/json' -d '{"phone":"+70000000000"}' \
  https://HOST/survey/api/v1/public/events/00000000-0000-0000-0000-000000000000/phone/call; done | sort | uniq -c
#    ожидаемо: сначала 404, затем 429

# 3. Cookie устройства ставится с Secure (X-Forwarded-Proto: https дошёл до Tomcat): после подтверждения телефона
#    в ответе /phone/verify заголовок Set-Cookie: SURVEYDEV=…; Secure; HttpOnly; SameSite=Lax

# 4. Схема A: на 10.0.0.4 временно добавить в server{}
#      log_format survey_ip '$remote_addr xff=[$http_x_forwarded_for] client=$survey_client_ip $request';
#      access_log /var/log/nginx/survey_ip.log survey_ip;
#    и открыть анкету с обычного устройства: client= должен быть вашим внешним адресом, а не адресом erp-web.
#    Схема B: на краевом nginx добавить в журнал $remote_addr и убедиться, что 429 приходит по адресу посетителя.
```

## Важно

- **`/survey/api/v1/sync/` блокируется на IIS** (правило «block sync from internet»). Это обязательно: запросы от IIS приходят
  на 10.0.0.4 с внутреннего адреса erp-web, и allow-лист внутреннего nginx их пропустил бы. 1С ходит на 10.0.0.4 напрямую,
  минуя IIS.
- `X-Forwarded-Proto=https` с IIS обязателен: по нему Spring ставит Secure-cookie и строит https-ссылки
  (map во внутреннем nginx пропускает заголовок дальше).
- При обновлении платформы 1С на erp-web этот сайт не зависит от wsisapi.dll — перепубликация не нужна.

## Альтернатива без IIS

Если на шлюзе/роутере можно сделать отдельный проброс: DNS `survey.bigcom.ru` → внешний IP, порт 443 → 10.0.0.4,
на 10.0.0.4 поднять TLS в nginx (серверный блок из `../nginx/survey-external.conf`, upstream 127.0.0.1:80 заменить на локальный)
с копией wildcard-сертификата. Тогда IIS в цепочке не участвует.
