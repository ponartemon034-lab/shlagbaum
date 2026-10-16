# Анализ оригинального приложения «Шлагбаум пульт гостевой доступ»

**Файл:** `Шлагбаум_143.0.apk` · размер 5.84 МБ · один `classes.dex`, без нативных библиотек
**Дата анализа:** 2026-10-16
**Инструменты:** `jadx 1.5.0` (декомпиляция в Java), `unzip`/`zipinfo`, разбор ресурсов.

> **Важно о достоверности.** Это статический анализ декомпилированного байткода. Код обфусцирован (короткие имена пакетов `z7`, `t7`, `d8` и т. п.), поэтому **исходники восстановлены не полностью**: имена переменных, часть потока управления и серверная часть недоступны. Всё, что ниже помечено как «предположение», проверено по косвенным признакам, но требует подтверждения. Серверную авторизацию я не обходил, чужие секреты не извлекал. Клиентский идентификатор Parse (`applicationId`) приведён как факт из APK — это **публичный** идентификатор, встроенный в каждую копию приложения, а не приватный ключ; закрытых ключей (masterKey, clientKey) в APK нет.

---

## 1. Паспорт приложения

| Параметр | Значение |
|---|---|
| package | `com.vetver.easybarrier` |
| versionCode / versionName | `143` / `143.0` |
| minSdkVersion | **24** (Android 7.0) |
| targetSdkVersion / compileSdk | **34** (Android 14) |
| installLocation | internalOnly |
| allowBackup | false |
| usesCleartextTraffic | **true** (+ `network_security_config`) |
| Разработчик (контакт) | `vetver7@gmail.com` (судя по тексту `sanctions` — соло-разработчик) |
| Монетизация | Google Play Billing 6.1.0 + AdMob (`ca-app-pub-8175961989826443`) |

Приложение опубликовано в Google Play (присутствует Play signing stamp, split-APK метаданные). Это розничный APK, а не отладочная сборка.

## 2. Технологический стек

- **Язык/UI:** Kotlin + Android Views (ViewBinding, Material Components 1.11, AppCompat 1.6.1). Не Compose.
- **Корутины:** kotlinx-coroutines 1.6.4.
- **DI:** Dagger 2.27.
- **Backend-клиент:** **Parse SDK** (`com.parse`) — Parse Platform, с **LocalDatastore** и **LiveQuery** (realtime-подписки через websocket).
- **Push:** Firebase Cloud Messaging (FCM). Проект Firebase: `easybarrier-10c35`, sender id `379590672699`.
- **Прочее Firebase:** Analytics, Crashlytics, Remote Config, Performance.
- **Карты/геозоны:** Google Maps + Play Services Location (функция авто-открытия по приближению).
- **Библиотеки UI:** Karumi Dexter (runtime-разрешения), Yalantis uCrop (обрезка фото шлагбаума), Glide (предположительно, для фото).
- **AGP:** 8.2.0.

## 3. Разрешения Android и что они обслуживают

| Разрешение | Назначение |
|---|---|
| `CALL_PHONE` | Инициировать исходящий вызов |
| `MANAGE_OWN_CALLS` | Самоуправляемый вызов через `TelecomManager` (ключевое — см. §6) |
| `READ_PHONE_STATE` | Отслеживать состояние вызова (CALL_STATE) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_PHONE_CALL` | Foreground-сервис типа `phoneCall` для звонка в фоне |
| `FOREGROUND_SERVICE_LOCATION` | Foreground-сервис геозоны (авто-открытие) |
| `ACCESS_FINE/COARSE/BACKGROUND_LOCATION` | Авто-открытие по приближению |
| `POST_NOTIFICATIONS` | Уведомления (Android 13+) |
| `WAKE_LOCK` | Удержание процессора при обработке команды |
| `RECEIVE_BOOT_COMPLETED`, `QUICKBOOT_POWERON` | Перезапуск после перезагрузки (`EventsReceiver`) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Исключение из Doze/оптимизации батареи — чтобы FCM и сервисы не убивались |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Сеть |
| `BLUETOOTH`, `BLUETOOTH_CONNECT` | Предположительно для авто-открытия/диагностики |
| `com.android.vending.BILLING` | Платные функции |
| `AD_ID` | AdMob |

Приложение **не запрашивает** роль дозвонщика по умолчанию (DefaultDialer) и **не использует** Accessibility Service. Звонок в фоне реализован «легальным» путём — самоуправляемый вызов (см. §6).

## 4. Экраны и навигация

Launcher-активность — `barlist.BarListActivity` (`singleTask`), она же обрабатывает deep-link `https://easybarrier-10c35.web.app/new.html` (Firebase Hosting; вероятно — добавление шлагбаума по ссылке).

Основные экраны (из манифеста):
- `BarListActivity` — главный список шлагбаумов, кнопки открытия.
- `addbar.AddBar` — добавление своего шлагбаума (владелец): имя, телефон, фото, геоточка.
- `addbar.AddRemoteBar` — добавление **чужого** шлагбаума по кодовому слову (гость).
- `share.ShareActivity` — создание гостевого доступа (кодовое слово/ссылка).
- `geopoint.*` — карта, настройки геозон авто-открытия, выбор сети, инфо.
- `utils.PreferencesActivity` — настройки; `PassHistoryActivity` — история операций; `AppLogActivity` — лог; `InfoActivity`, `SupportInfo`, `SanctionsActivity`, `AdvertisementActivity`, `AdShowActivity`.
- `imagepicker.ImagePickerActivity` + `UCropActivity` — выбор/обрезка фото.

Сервисы/приёмники:
- `telephoneparse.MyFirebaseMessagingService` (FCM, `foregroundServiceType=phoneCall`) — **приём push и инициирование звонка**.
- `telephoneparse.PhoneCallServiceParse` (`phoneCall`) — сопровождение активного вызова, обновление статуса.
- `fcmkeepalive.FcmKeepAlive` / `FcmKeepAlive2` — keep-alive (отдельный процесс `:service`).
- `geopoint.ProximityService` (`location|phoneCall`) — авто-открытие по геозоне.
- `geopoint.EventsReceiver` — перезапуск после BOOT_COMPLETED.

## 5. Сетевое взаимодействие и серверная часть

**Backend — самостоятельно развёрнутый Parse Server** (не parse.com).

Инициализация (`z7/v.java:194`):
```
Parse.initialize(new Parse.Configuration.Builder(context)
    .applicationId("EASYBARRIER")
    .clientKey(null)
    .maxRetries(0)
    .allowCustomObjectId()
    .server("https://178.154.207.248:543/parse/")
    .enableLocalDataStore()
    .build());
```

- **Сервер:** `https://178.154.207.248:543/parse/` — IP на хостинге Yandex. TLS c **закреплённым сертификатом** (`res/raw/cert_yandex.pem`, `network_security_config` доверяет только этому домену). `usesCleartextTraffic=true` оставлен, но пиннинг ограничивает доверие одним сертификатом.
- **Аутентификация:** анонимная (`ParseAnonymousUtils`) — у пользователя нет логина/пароля, личность = анонимный ParseUser (класс `Player`/`PUser`). На сервер уходит `devicetoken` (FCM-токен), модель устройства, SDK, локаль и т. п.
- **Realtime:** Parse **LiveQuery** (websocket) — клиент подписывается на изменения своих объектов `pass`/`bar`/`share` и видит смену статуса операции в реальном времени.
- **Доп. ключ:** `res/raw/key_pub2.pem` — публичный RSA-ключ (предположительно для проверки подписи/шифрования полей команды; приватная часть на сервере — недоступна).

### Облачные функции Parse (серверная логика)
Найдены вызовы `ParseCloud.callFunction`:
- **`checkCodeWord`** — вход: `{ver, userid, codeword}`; сервер проверяет кодовое слово и возвращает данные шлагбаума (имя, телефон, фото) и `linkid`.
- **`linkRemoteBar`** — вход: `{ver, userid, linkid}`; сервер привязывает удалённый шлагбаум к пользователю.

> **Вывод по авторизации:** проверка кодового слова и выдача доступа происходят **на сервере** (Cloud Code). Клиент не содержит алгоритма генерации/валидации кодовых слов — он их только отправляет. Воспроизвести это без сотрудничества с владельцем сервера нельзя, и я этого не делал.

### Схема данных Parse (классы и ключевые поля)

| Класс | Назначение | Ключевые поля |
|---|---|---|
| `Player` (PUser) | пользователь/устройство | `devicetoken` (FCM), `appver`, `devmodel`, `devsdk`, `lang`, `whitelist`, `gracebar`, `graceshare`, `RunCount`, `RunCountRemote`, `usagecountremotefail`, биллинг (`orderid`, `orderdetails`) |
| `bar` | шлагбаум | `name`, `phone`, `codeword`, `location`(GeoPoint), `masterbar`, `masteruser`, `mastershare`, `type`, `autoopenenabled`, `sharecount`, `imageid`, `lastpassstatus`, `lastpasstime` |
| `share` | гостевой доступ | (связь bar↔гость; `codeword`, `sharecount`) |
| `pass` | **операция открытия** (команда) | `status`, `phone`, `type`, `time`, `geopoint`, `codeword`, `bar`, `masteruser`, `masterbar`, `mastershare`, `timereceive`, `srvappbucket`, `srvappsdk`, `srvappver`, `proximitycall` |
| `cfg` | конфигурация | — |

## 6. Как инициируется звонок — главный механизм

Тип вызова (`telephoneparse/CallType`): **`interactive`** (нажал кнопку на своём телефоне), **`autoopen`** (геозона), **`remotefcm`** (удалённо по push). **Удалённый сценарий в оригинале уже есть.**

Поток удалённого открытия (`MyFirebaseMessagingService.onMessageReceived`):
1. Приходит **FCM data-сообщение**. Проверяется `from == 379590672699` (свой sender).
2. Проверяется, что в payload есть `mastersecret` и `type == "openbar"`.
3. Из payload берутся `passid`, `masterbar`, `name`, `codeword`, `mastersecret`.
4. **Номер шлагбаума в push НЕ передаётся** — он берётся локально: `phone_in = localPrefs.g(masterbar)` (поиск по идентификатору `masterbar` в локальном хранилище принимающего телефона). Это удачное privacy-решение: номер хранится на устройстве, которое звонит.
5. `startForeground(5588, notification, FOREGROUND_SERVICE_TYPE_PHONE_CALL)` — поднимается foreground-сервис типа «звонок».
6. `phoneCall(...)` с защитами: отказ при роуминге (если включена настройка), отказ если телефон уже в разговоре (`isInCall`), отказ если номер пустой. Статусы ошибок: `erPhoneInRoaming`, `erPhoneInCall`, `erPhoneNotFound`, `erServiceBusy`.
7. `makeCall()` ставит вызов через **`TelecomManager.placeCall(Uri "tel:номер", bundle)`** с выбором SIM через `PHONE_ACCOUNT_HANDLE` (поддержка двух SIM по `SubscriptionManager`). Это и есть легальный способ позвонить из фона/с заблокированного экрана: самоуправляемый вызов (`MANAGE_OWN_CALLS`) + foreground-сервис `phoneCall`, **без** ACTION_CALL Activity и **без** роли дозвонщика.
8. Статус операции пишется в Parse (`pass.status`): `connecting → startingCall → ...`; гость видит это через LiveQuery в реальном времени. Также пишутся `timereceive`, `srvappbucket` (App Standby Bucket), `srvappsdk`, `srvappver`.

**Защита от «застревания» и злоупотреблений в оригинале:** флаг `isSeriviceBusy` (одновременно один вызов), счётчик `X` (квота вызовов, связана с биллингом), таймер (CountDownTimer) на длительность.

> Оригинал **не подтверждает** физическое открытие шлагбаума — он знает только, что вызов инициирован. Статус отражает стадию звонка, а не «шлагбаум открыт». Это важно и для новой версии (п. 7 ТЗ).

## 7. Сценарий добавления шлагбаума по кодовому слову

`AddRemoteBar` (гость):
1. Получает `shareCode` (кодовое слово) из Intent/deep-link.
2. Вызывает Cloud `checkCodeWord({ver:"1.0", userid, codeword})`.
3. Сервер возвращает данные шлагбаума → показывается карточка (имя/телефон/фото) и `linkid`.
4. По кнопке «принять» — Cloud `linkRemoteBar({ver, userid, linkid})` → шлагбаум добавляется в список.

Владелец создаёт доступ в `ShareActivity` (генерация кодового слова/ссылки — серверная).

## 8. Что установлено достоверно, а что — нет

**Достоверно (из APK):**
- Стек, разрешения, структура экранов/сервисов.
- Backend = самостоятельный Parse Server по указанному адресу; анонимная аутентификация; LiveQuery; FCM-push.
- Механизм звонка: `TelecomManager.placeCall` + foreground-сервис `phoneCall` + выбор SIM.
- Схема объектов `pass/bar/share/Player` и поля.
- Имена и сигнатуры Cloud-функций `checkCodeWord`, `linkRemoteBar`.
- FCM payload удалённого открытия (`type=openbar`, `passid`, `masterbar`, `mastersecret`, `codeword`, `name`).

**Не установлено (остаётся на сервере / не извлекалось):**
- Алгоритм генерации и валидации кодовых слов (Cloud Code на сервере).
- Как именно сервер решает, какому устройству слать push, и как формирует `mastersecret`.
- Приватные ключи, схема прав на сервере, точный формат ответов Cloud-функций.
- Логика биллинга/квот на стороне сервера.

**Способ проверки неизвестного:** только при сотрудничестве с владельцем системы (`vetver7@gmail.com`) — получить описание Cloud-функций и схемы, либо официальный API. Реверс серверной части недопустим и не выполнялся.

## 9. Выводы для новой версии

1. **Архитектура, которую хочет пользователь (A→интернет→B→звонок), в оригинале уже реализована** как `remotefcm`: сервер шлёт FCM data-push, принимающий телефон поднимает foreground-сервис `phoneCall` и звонит через `TelecomManager.placeCall`. Это подтверждает техническую осуществимость и даёт эталон приёмов.
2. **Нельзя переиспользовать чужой backend.** Production Parse-сервер и его Cloud-функции закрыты; кодовые слова валидируются там. Поэтому новую версию с функцией «звонок через второй телефон» нужно строить на **своём** backend (совместимом по идее), а совместимость со старым кодовым словом возможна только через официальный API владельца.
3. **Ключевой приём звонка в фоне подтверждён:** `MANAGE_OWN_CALLS` + self-managed `PhoneAccount`/ConnectionService + foreground-сервис типа `phoneCall`, запускаемый из `FirebaseMessagingService` по data-push. Роль дозвонщика по умолчанию и Accessibility не нужны.
4. **Безопасное хранение номера:** номер шлагбаума держится на звонящем устройстве, в команде передаётся только идентификатор — это переносим в новую версию.
5. **Статусная модель** `pass.status` ложится на конечный автомат операции из ТЗ (CREATED→…→COMPLETED) с отдельными состояниями ошибок.
