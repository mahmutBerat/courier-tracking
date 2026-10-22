FROM eclipse-temurin:25-jdk-ubi10-minimal AS builder

WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre-ubi10-minimal

WORKDIR /app

RUN chown -R 10001:0 /app
COPY --from=builder --chown=10001:0 /workspace/build/libs/*.jar app.jar

USER 10001
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
