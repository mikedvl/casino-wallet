FROM eclipse-temurin:21.0.12_8-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY src/ src/
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21.0.12_8-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/build/libs/casino-wallet.jar application.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
