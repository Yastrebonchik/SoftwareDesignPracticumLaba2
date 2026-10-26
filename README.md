# Лабораторная работа №2: Docker

Одна лабораторная из двух частей: сборка образа приложения и запуск стека через Docker Compose.

Приложение — API заметок на Java 21 и Spring Boot 3.5.16. Заметку можно создать, прочитать и удалить. Есть небольшая веб-страница на `/`, а `/health` проверяет соединение с базой данных.

## Что понадобится

- Docker Engine и Docker Compose v2 или новее.
- Свободный порт 8080 для отдельного контейнера и порт 80 для Compose.
- `curl` для ручных проверок; Python 3 для автоматической проверки стека.
- Bash для скрипта публикации.

Java и Maven на хосте не нужны: приложение собирается внутри Docker. Команды выполняются из корня этого репозитория. Проверено на Ubuntu 24.04, архитектура x86_64.

## Часть 1. Отдельный образ

```bash
docker build -t notes-api:1.0.0 .
docker run -d --name notes-standalone \
  -p 8080:8080 \
  -e JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60.0 \
  notes-api:1.0.0
```

Дождаться запуска JVM, затем проверить:

```bash
docker ps --filter name=notes-standalone
curl -i http://localhost:8080/health
curl -i -X POST http://localhost:8080/notes \
  -H 'Content-Type: application/json' \
  -d '{"text":"Моя первая заметка"}'
curl http://localhost:8080/notes
docker exec notes-standalone id
```

`/health` возвращает HTTP 200 и `{"status":"UP"}`. Пользователь процесса — `app`, UID и GID — 10001. В браузере можно открыть `http://localhost:8080`.

Без внешней БД приложение использует H2 в памяти. Это позволяет запустить первую часть одной командой `docker run`. Данные H2 исчезают при остановке приложения. Во второй части используется PostgreSQL с постоянным томом.

```bash
docker stop notes-standalone
docker rm notes-standalone
```

### Как устроен Dockerfile

1. `FROM ... AS builder` выбирает Maven и JDK 21 для сборки. Сначала копируется `pom.xml` и скачиваются зависимости. Изменение Java-кода не заставляет загружать их заново.
2. После копирования `src` команда `mvn package` компилирует приложение, запускает три теста и создаёт `app.jar`. Ошибка теста прерывает сборку образа.
3. Второй `FROM` начинает runtime-слой на основе Alpine с JRE 21. Maven, JDK, исходники и тесты в него не копируются.
4. `addgroup` и `adduser` создают пользователя `app`; `USER app:app` назначает его для запуска приложения и healthcheck.
5. `ENV` задаёт порт и настройки памяти JVM. `JAVA_TOOL_OPTIONS` Java читает сама: shell-обёртка для подстановки переменных не нужна.
6. `EXPOSE 8080` описывает порт приложения. Публикует его на хост только `-p` или раздел `ports` в Compose.
7. `HEALTHCHECK` через `wget` обращается к `/health`. JSON-массив — exec-форма команды. Есть интервал проверок и время на запуск JVM.
8. `ENTRYPOINT ["java", "-jar", "/app/app.jar"]` запускает Java напрямую. JVM получает сигналы остановки от Docker.

Оба базовых образа указаны с тегами и SHA-256 digest. Тег объясняет, какой образ используется; digest фиксирует его содержимое. Версии зависимостей определяются фиксированной версией Spring Boot, SNAPSHOT-зависимостей нет. Maven получает фиксированное время для содержимого JAR. `.dockerignore` исключает `.env`, Git, локальные сборки и документацию из контекста сборки.

## Часть 2. Nginx + приложение + PostgreSQL

Создать свой файл окружения и заменить пароль-заглушку:

```bash
cp .env.example .env
nano .env
chmod 600 .env
docker compose up -d
```

При первом запуске Compose сам собирает образ приложения, если его ещё нет. После изменения исходников использовать `docker compose up -d --build`.

Дождаться состояния `healthy` у трёх сервисов:

```bash
docker compose ps
curl -i http://localhost/health
curl -i -X POST http://localhost/notes \
  -H 'Content-Type: application/json' \
  -d '{"text":"Эта заметка хранится в PostgreSQL"}'
curl http://localhost/notes
```

В браузере открыть `http://localhost`. Все HTTP-запросы идут через Nginx. Для обычного запуска достаточно `docker compose up -d`; вариант с ожиданием готовности — `docker compose up -d --wait --wait-timeout 180`.

### Сети и порты

```text
Хост:80 -> nginx:80 -> app:8080 -> db:5432
           frontend ---+         |
                       +-- backend
```

- `nginx` подключён только к `frontend` и публикует `80:80`.
- `app` подключён к `frontend` и `backend`, без `ports`.
- `db` подключён только к `backend`, без `ports`.
- `backend` имеет `internal: true`.

Запись `8080/tcp` или `5432/tcp` в `docker compose ps` означает внутренний порт контейнера. Публикация на хост выглядит как `0.0.0.0:80->80/tcp`. У приложения и БД такой публикации нет.

`depends_on` с `condition: service_healthy` ждёт готовности PostgreSQL перед запуском приложения и готовности приложения перед Nginx. `/health` делает `SELECT 1`: при недоступной БД возвращается HTTP 503 и статус `DOWN`. Проверка Nginx также проходит до приложения и БД.

Nginx использует встроенный DNS Docker `127.0.0.11` и обновляет адрес `app`. Поэтому после пересоздания контейнера приложения прокси не остаётся с его старым IP.

Приложение работает от non-root пользователя, с файловой системой только для чтения и удалёнными Linux capabilities. Для временных файлов JVM выделен отдельный `tmpfs` в `/tmp`.

В Compose оставлено `version: "3.8"` по формулировке задания. Современный Compose использует Compose Specification и выводит предупреждение, что поле `version` устарело и игнорируется. Это предупреждение не мешает работе.

### Переменные окружения

| Переменная | Где используется | Значение |
| --- | --- | --- |
| `POSTGRES_DB` | `.env`, PostgreSQL, URL приложения | Имя базы |
| `POSTGRES_USER` | `.env`, PostgreSQL, приложение | Пользователь базы |
| `POSTGRES_PASSWORD` | `.env`, PostgreSQL, приложение | Собственный пароль |
| `DB_URL` | Приложение | В Compose: `jdbc:postgresql://db:5432/<база>`; без настройки: H2 в памяти |
| `DB_USER` | Приложение | Пользователь JDBC; без настройки: `sa` |
| `DB_PASSWORD` | Приложение | Пароль JDBC; без настройки пустой для H2 |
| `JAVA_TOOL_OPTIONS` | JVM | По умолчанию: максимум 75% доступной контейнеру памяти |

`.env.example` содержит только образец. Реальный `.env` исключён из Git и Docker build context. Для запуска на другой машине нужно создать свой `.env`; публиковать его не нужно. Переменные БД обязательны для Compose: если их нет, он остановится с объяснением.

### Проверка сохранения данных

Создать заметку командами выше, затем выполнить:

```bash
docker compose restart
curl http://localhost/notes

docker compose down
docker compose up -d --wait --wait-timeout 180
curl http://localhost/notes
```

После `restart` запрос нужно делать, когда сервисы снова станут `healthy`. Заметка должна сохраниться и после пересоздания контейнеров. Данные PostgreSQL находятся в именованном томе `pgdata`; по умолчанию Docker добавляет к имени префикс проекта.

`docker compose down` удаляет контейнеры и сети, сохраняя том. Команда `docker compose down -v` удаляет и том с заметками — для обычной остановки она не нужна. PostgreSQL использует параметры `.env` при первоначальном создании базы: смена пароля в `.env` сама по себе не меняет пароль существующего пользователя в томе.

### API и устройство Java-кода

| Метод | Путь | Результат |
| --- | --- | --- |
| GET | `/` | Веб-страница заметок |
| GET | `/health` | 200 `UP` или 503 `DOWN` |
| GET | `/notes` | Список заметок по ID |
| GET | `/notes/{id}` | Заметка или 404 |
| POST | `/notes` | Создание, HTTP 201, заголовок `Location` |
| DELETE | `/notes/{id}` | Удаление, HTTP 204; если заметки нет — 404 |

Для POST нужен JSON `{"text":"Текст заметки"}`. Пустой текст, отсутствие поля и текст длиннее 1000 символов дают HTTP 400.

`NotesApplication` запускает Spring Boot. `Note` — Java record с ID и текстом. `NotesController` обрабатывает HTTP-запросы и обращается к БД через `JdbcTemplate`. SQL-параметры передаются через `?`, без склеивания пользовательского текста с SQL. `schema.sql` создаёт таблицу, если её ещё нет, не удаляя существующие данные. Настройки JDBC берутся из окружения в `application.properties`.

## Сборка и публикация в registry

Dockerfile достаточно для локальной сборки. Отдельный скрипт добавлен потому, что в ТЗ прямо требуется «скрипт для сборки и выгрузки докер образа в реджистри».

Для Docker Hub заменить `YOUR_DOCKERHUB_LOGIN` на свой логин:

```bash
docker login
bash scripts/build_push.sh docker.io/YOUR_DOCKERHUB_LOGIN/notes-api:1.0.0
```

Для другого registry указать его адрес в `docker login` и полный адрес образа в аргументе скрипта. Для публикации нужны аккаунт и права записи. Токены в файлы проекта добавлять не нужно.

Скрипт получает имя образа, проверяет наличие явного тега, собирает его через `docker build`, затем выполняет `docker push`. `set -euo pipefail` останавливает выполнение при ошибке; после неудачной сборки push не запускается. Скрипт находит папку проекта по своему расположению, поэтому его можно вызывать из любой директории.

Фактический push проверен на временном локальном registry; внешняя публикация требует отдельно выбранного registry и авторизации.

## Автоматическая проверка

```bash
python3 scripts/verify.py
```

Скрипт использует только стандартную библиотеку Python. Он собирает и поднимает стек, проверяет API и неправильный ввод, создаёт отдельную тестовую заметку, проверяет её после `restart` и после `down` / `up`, проверяет сети и опубликованные порты через `docker inspect`. Затем временно останавливает БД, проверяет HTTP 503, восстанавливает стек и удаляет свою тестовую заметку. Остальные заметки не удаляются. В конце стек остаётся запущенным.

Проверка временно прерывает доступность этого учебного стека. Результаты ручных и автоматических проверок находятся в `docs/`.

## Скриншоты и результаты

- [Отдельный контейнер](docs/standalone-test.txt).
- [Полная проверка Compose](docs/compose-test.txt).
- [Сборка и push в локальный registry](docs/registry-test.txt).
- [Статус контейнеров и ответы API](docs/screenshots/containers.jpg).
- [Приложение в браузере](docs/screenshots/application.jpg).

## Диагностика и остановка

```bash
docker compose logs --tail=100 app
docker compose logs --tail=100 nginx
docker compose logs --tail=100 db
docker compose stop
docker compose start
```

Для просмотра с Windows, если у виртуалки проброшен только SSH:

```bash
ssh -p 2222 -N -L 18080:127.0.0.1:80 alex@127.0.0.1
```

Пока эта SSH-сессия открыта, приложение доступно на Windows по `http://localhost:18080`.

## Отправка преподавателю через Git

Локально подготовлены ветки `master` (начальный пустой коммит) и `lab2` (вся лабораторная). Оба Docker-задания входят в один PR.

1. На GitHub создать пустой репозиторий `SoftwareDesignPracticumLaba2`, без автоматически создаваемых README, лицензии и `.gitignore`.
2. Из этой папки выполнить:

```bash
git remote add origin git@github.com:Yastrebonchik/SoftwareDesignPracticumLaba2.git
git push -u origin master
git push -u origin lab2
```

3. Создать один pull request: `lab2` → `master`. В описании указать обе части и результаты проверок. Исходники, Dockerfile, Compose, скрипт публикации, README и скриншоты уже входят в ветку.
4. По правилам курса отправить преподавателю в Telegram: `ФИО Лаба 2 <ссылка на PR>`.

Репозиторий на GitHub, push, PR и отправка сообщения здесь не выполнялись: эти действия остаются за автором работы.

## Документация

- [ТЗ: вкладка «Лабы», две строки лабораторной №2](https://docs.google.com/spreadsheets/d/1CtFoGif7L3c-Cgjskkh-UFhLi3AC1XDzP9FBntDst88/edit#gid=279456130).
- [Spring Boot 3.5: требования к Java и Maven](https://docs.spring.io/spring-boot/3.5/system-requirements.html).
- [Docker Compose: порядок запуска и healthcheck](https://docs.docker.com/compose/how-tos/startup-order/).
- [Compose Specification](https://docs.docker.com/reference/compose-file/).
