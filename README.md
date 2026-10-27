# Лабораторная работа №2: Docker

Приложение заметок на Spring Boot. Обе части лабораторной используют один образ.
Нужны Docker, Docker Compose, Bash и curl. Все команды выполняются из корня проекта.

## Часть 1. Сборка и запуск образа

```bash
docker build -t notes-api:1.0.0 .
docker run -d --name notes-standalone \
  -p 8080:8080 \
  -e JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60.0 \
  notes-api:1.0.0
```

После запуска приложения:

```bash
curl -i http://localhost:8080/health
docker exec notes-standalone id
```

Ожидается HTTP 200 и пользователь `app` (UID 10001). Веб-страница: http://localhost:8080/.
Dockerfile содержит стадии builder/runtime, фиксированные образы, Alpine runtime,
non-root пользователя, ENV, EXPOSE, HEALTHCHECK и ENTRYPOINT в exec-форме.

Перед проверкой второй части остановить отдельный контейнер:

```bash
docker stop notes-standalone
docker rm notes-standalone
```

## Часть 2. Запуск через Docker Compose

Если `.env` ещё нет, создать его из примера и установить свой пароль PostgreSQL:

```bash
cp .env.example .env
nano .env
chmod 600 .env
```

Запустить стек и дождаться `healthy` у трёх сервисов:

```bash
docker compose up -d
docker compose ps
curl -i http://localhost/health
```

Ожидается HTTP 200. Веб-страница: http://localhost/.
Compose 3.8 запускает `nginx`, `app` и PostgreSQL (`db`), использует сети
`frontend`/`backend` и том `pgdata`. На хост опубликован только порт 80 Nginx.
Реальный `.env` исключён из Git; `.env.example` включён в репозиторий.

Для проверки сохранения данных создать заметку в браузере и выполнить:

```bash
docker compose restart
docker compose up -d --wait --wait-timeout 180
curl http://localhost/notes
```

Заметка должна сохраниться. Остановка с сохранением данных: `docker compose down`.

## Скрипт сборки и публикации образа

Заменить `YOUR_DOCKERHUB_LOGIN` на свой логин:

```bash
docker login
bash scripts/build_push.sh docker.io/YOUR_DOCKERHUB_LOGIN/notes-api:1.0.0
```

Исходники приложения находятся в `src/`.
Скриншоты: [статус контейнеров](docs/screenshots/containers.jpg),
[работающее приложение](docs/screenshots/application.jpg).
