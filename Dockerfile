# Multi-stage build for the RCS Simulator (Java 21 / Spring Boot 3.3.4 / Maven).
#
# Render (and most non-Java PaaS hosts) has no native Maven/JDK buildpack -
# only Node, Python, Ruby, Go, Rust, Elixir, and Docker. Deploying a Maven
# project there means: build the jar inside a container, run it in another
# container. That's what this file does, so the *runtime* image ships a JRE
# only (smaller, no Maven, no build toolchain sitting around in production).

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the POM first and let Maven resolve dependencies into a cached layer
# before copying source - so an unrelated source change doesn't force
# re-downloading the entire dependency tree on every build.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Matches <build><finalName>rcs-simulator</finalName></build> in pom.xml.
COPY --from=build /build/target/rcs-simulator.jar app.jar

# Informational only - Render (and most hosts) inject PORT and route to it
# regardless of EXPOSE; the app itself reads PORT via
# server.port=${PORT:8080} in application.properties.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
