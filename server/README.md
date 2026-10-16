# shlagbaum-relay-server

Минимальный управляемый backend (Вариант C): регистрация двух устройств, привязка по коду-приглашению, **очередь команд открытия как источник истины**, доставка на телефон B через FCM data-push с резервным pull, конечный автомат операции, защита от повторов, ограничение частоты, аудит без секретов.

## Запуск локально (без Firebase — режим `pull-only`)

```bash
cd server
npm install --omit=optional
npm start            # слушает :8080 (PORT, DB_PATH — через env)
```

В режиме `pull-only` push не отправляется, телефон B забирает команды через `GET /v1/commands/pending`. Этого достаточно для проверки интерфейса, передачи команд и конечного автомата без внешних сервисов (ТЗ п. 12).

## Самопроверка (сквозной тест автомата, без устройств)

```bash
DB_PATH=:memory: node src/selftest.js     # 20 проверок, 0 провалов
```

## Включение FCM-доставки (боевой режим `fcm+pull`)

1. Создать проект Firebase, включить Cloud Messaging.
2. Сгенерировать приватный ключ сервис-аккаунта (Project settings → Service accounts). **Это серверный секрет — в APK его не кладём.**
3. Запустить с переменными:

```bash
GOOGLE_APPLICATION_CREDENTIALS=/path/serviceAccount.json \
  npm install && npm start      # firebase-admin подтянется как optionalDependency
```

`GET /v1/health` покажет `"fcm":"fcm+pull"`.

## API

| Метод | Путь | Кто | Назначение |
|---|---|---|---|
| GET | `/v1/health` | — | статус, режим FCM |
| POST | `/v1/devices/register` | — | регистрация, выдаёт `authToken` + роль |
| POST | `/v1/devices/token` | устройство | обновить FCM-токен / «в сети» |
| POST | `/v1/pairings/invite` | B | создать 6-значный код приглашения |
| POST | `/v1/pairings/redeem` | A | погасить код → создать привязку |
| GET | `/v1/pairings` | любой | список привязок устройства |
| POST | `/v1/pairings/:id/revoke` | A или B | отозвать привязку (+отмена активных команд) |
| POST | `/v1/commands` | A | отправить команду «открыть» (авторизация+очередь+push) |
| GET | `/v1/commands/pending` | B | забрать незавершённые команды (резервный pull) |
| GET | `/v1/commands/:id` | сторона пары | статус команды (опрос для A) |
| POST | `/v1/commands/:id/ack` | B | переход автомата: RECEIVED / CALL_REQUESTED / COMPLETED / CALL_BLOCKED / ERROR |
| GET | `/v1/audit` | устройство | журнал (без токенов/секретов) |

Аутентификация: заголовок `Authorization: Bearer <authToken>`.

## Конечный автомат

```
CREATED → AUTHORIZED → DISPATCHED → RECEIVED → CALL_REQUESTED → COMPLETED
                           ├─ EXPIRED (TTL команды, по умолчанию 60 с)
                           ├─ REVOKED (отозвана привязка)
                           ├─ DUPLICATE_IGNORED (повтор того же commandId)
                           ├─ NOT_ALLOWED / DEVICE_OFFLINE
                           ├─ CALL_BLOCKED (Android не дал позвонить)
                           └─ ERROR
```

- **Идемпотентность:** повторная отправка того же `commandId` возвращает существующую команду — второго звонка не будет.
- **Anti-replay:** переходы завершённой (терминальной) команды игнорируются; у каждой команды `nonce` и `expiresAt`.
- **Права:** сервер проверяет, что команду шлёт именно контроллер этой пары и привязка не отозвана. Разделены права: видеть (список привязок) / инициировать (команда) / привязывать (invite у B) / выдавать-отзывать доступ (invite/revoke).
- **Номер шлагбаума на сервер не попадает** — в команде только `barRef` (метка). Реальный номер хранится на телефоне B.

## Развёртывание (прототип, бесплатно)

Подойдёт любой бесплатный тариф Node-хостинга (Render / Fly.io / Railway) или своя VPS. Переменные окружения: `PORT`, `DB_PATH` (том для SQLite), `GOOGLE_APPLICATION_CREDENTIALS`. TLS обеспечивает платформа хостинга. Резервное поведение при сбое push — pull со стороны B.

> SQLite достаточно для прототипа и двух устройств. Для многих пользователей — заменить на Postgres (схема в `src/db.js` переносится 1:1).
