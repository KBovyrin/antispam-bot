# --- Сборка ---
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Кеш зависимостей: pom.xml копируется первым (системный mvn, mvnw не нужен)
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline

COPY src/ ./src/
RUN mvn -q -B package -DskipTests

# --- Рантайм ---
FROM docker.io/library/eclipse-temurin:21-jre
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
