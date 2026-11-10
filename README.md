# Keeper

Android-приложение (нативная обёртка + WebView) и сервер в одном файле `server.py`.

* Регистрация / вход по логину и паролю, **ПИН-код 4 цифры**, **биометрия**, блокировка при сворачивании.
* Вкладки **Кошелёк / Трейдинг / Браузер**: отправка, пополнение (QR), обмен, стейкинг, история.
* **P2P**: объявления «купить/продать», сделка с эскроу (крипта замораживается), чат, таймер оплаты, споры решает админ.
* **Админка** на сервере: `https://ТВОЙ-САЙТ/admin` (пользователи, балансы, заявки на вывод, P2P-сделки и споры, активы и цены, настройки, загрузка APK).
* **Обновление поверх без потери данных**: подпись всегда одна (`signing/keeper.jks`), `versionCode` растёт с каждой сборкой, приложение само находит новую версию на сервере, скачивает и ставит поверх. Всё данные хранятся в БД на сервере, а ПИН/токен — в памяти приложения, которая при обновлении сохраняется.

> Это внутренний реестр балансов в БД (леджер), а не блокчейн. Пополнение — через админку, внешний вывод — заявка, которую подтверждает админ.

## 1. Сервер (PythonAnywhere)
1. Загрузи `server.py` в папку, например `/home/ИМЯ/keeper/`.
2. В начале файла поменяй `ADMIN_PASS` и `UPLOAD_TOKEN` (или задай переменные окружения `KEEPER_ADMIN_PASS`, `KEEPER_UPLOAD_TOKEN`).
3. Web → Add a new web app → Flask. В WSGI-файле:
   ```python
   import sys
   sys.path.insert(0, '/home/ИМЯ/keeper')
   from server import app as application
   ```
4. Reload. Открой `https://ИМЯ.pythonanywhere.com/admin` и войди как `admin`.
   БД `keeper.db` и папка `apk/` создадутся рядом с `server.py` сами.

## 2. Приложение (GitHub)
1. Залей содержимое этой папки в **приватный** репозиторий (в `signing/` лежит ключ подписи — не делай репозиторий публичным и не теряй ключ: с другим ключом обновление поверх не встанет).
2. Укажи адрес сервера одним из способов:
   * в `gradle.properties`: `KEEPER_SERVER_URL=https://ИМЯ.pythonanywhere.com`, или
   * GitHub → Settings → Secrets and variables → Actions → **Variables** → `KEEPER_SERVER_URL`.
   (Если не указать — приложение при первом запуске само спросит адрес сервера.)
3. Чтобы сборка сама выкладывалась на сервер и приложения предлагали обновиться, добавь **Secrets**:
   `KEEPER_UPLOAD_URL` = `https://ИМЯ.pythonanywhere.com` и `KEEPER_UPLOAD_TOKEN` = тот же, что `UPLOAD_TOKEN` в `server.py`.
4. Push в `main` (или Actions → Build Keeper APK → Run workflow). APK будет в Artifacts (`Keeper-vc<код>.apk`).
5. Первый раз поставь APK вручную. Дальше: push → сборка → сервер → в приложении появится «Обновление» → «Обновить».
   Без авто-выкладки: админка → «Обновления APK» → загрузить файл `Keeper-vc<код>.apk` (код берётся из имени).

## Структура
```
.github/workflows/build-apk.yml   сборка в GitHub Actions
app/                              Android-модуль (MainActivity.java, assets/index.html — весь интерфейс)
icon/icon.png                     исходная иконка (слои adaptive-иконки в app/src/main/res/drawable-nodpi)
signing/                          keeper.jks + keystore.properties
server.py                         (отдельно) сервер: API + БД + админка + раздача APK
```
