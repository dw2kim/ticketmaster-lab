# The app as a container image. Render (and CI) build it from this file.
#
# Two stages:
#   build    a full JDK + Maven: compiles the code and makes one runnable jar
#   runtime  only a Java runtime + that jar: a smaller image with fewer tools to attack
#
# Build and run locally:
#   docker build -t ticketmaster-lab .
#   docker run --rm -p 8080:8080 -e DB_HOST=host.docker.internal -e REDIS_URL=redis://host.docker.internal:6379 ticketmaster-lab

FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
# Dependencies first: this layer is reused as long as pom.xml does not change.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Tests run in CI (GitHub Actions), where Docker is available for Testcontainers.
RUN ./mvnw -B -q -DskipTests package && cp target/ticketmaster-lab-*.jar app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
# Do not run as root inside the container.
RUN useradd --system --uid 10001 app
USER app
COPY --from=build /app/app.jar app.jar
EXPOSE 8080
# Use up to 75% of the container's memory for the Java heap (Render's small plans have 512 MB).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
