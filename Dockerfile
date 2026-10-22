# --- Сборка ---
# Образ запинен по digest (N9, supply-chain): tag плавающий, digest фиксирует
# ровно то, что проверялось. Обновление: skopeo inspect --format '{{.Digest}}'
# docker://docker.io/library/maven:3.9-eclipse-temurin-21 → заменить ниже.
FROM docker.io/library/maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /app

# Кеш зависимостей: pom.xml копируется первым (системный mvn, mvnw не нужен)
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline

COPY src/ ./src/
RUN mvn -q -B package -DskipTests

# --- Рантайм ---
# Пин по digest — как для build-стадии (обновление: skopeo inspect --format
# '{{.Digest}}' docker://docker.io/library/eclipse-temurin:21-jre)
FROM docker.io/library/eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99
WORKDIR /app

# Пользователь без root и его каталог для БД (иначе SQLite не создаст файл на volume)
# procps нужен для healthcheck (pgrep) в compose
RUN apt-get update \
    && apt-get install -y --no-install-recommends procps \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home bot \
    && mkdir -p /data \
    && chown -R bot:bot /data
USER bot

COPY --from=build /app/target/antispam-bot.jar app.jar

# БД SQLite внутри тома
ENV DB_URL=jdbc:sqlite:/data/antispam.db
VOLUME /data

ENTRYPOINT ["java", "-jar", "app.jar"]
