# Публикация survey наружу через IIS (erp-web) + ARR

Схема, когда внешний адрес компании проброшен на IIS (erp-web), а сервис живёт на 10.0.0.4:

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
5. Создать пустой каталог `C:\inetpub\survey-proxy`, положить туда `web.config` из этой папки.
6. Новый сайт **survey-proxy**: физический путь — этот каталог; биндинг **https, host name `survey.bigcom.ru`, порт 443,
   SNI = ✔, сертификат — wildcard `*.bigcom.ru`** (тот же, что на 1cbig). Пул приложений — любой (No Managed Code).
7. Проверка: `https://survey.bigcom.ru/survey/` открывается; `https://survey.bigcom.ru/survey/api/v1/sync/events/x/status` → 404.

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
