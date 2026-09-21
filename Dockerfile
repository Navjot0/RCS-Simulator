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

# No -Xms/-Xmx would mean HotSpot ergonomics: Xms defaults to ~1/64 of
# available memory, Xmx to ~1/4 - a tiny starting heap that has to grow
# several times as soon as real request volume hits it. Heap expansion under
# G1 (the default collector) happens as part of a GC cycle, which briefly
# stops every thread in the JVM - including the one thread that calls
# accept() on the server socket. That pause is what was producing a tight
# burst of java.net.ConnectException seen only in the first couple of
# seconds of every load test (never later, no matter how high sustained TPS
# climbed): connections arriving while the JVM is paused for a resize sit in
# the OS backlog until it's full, then get refused outright, with this
# application never getting a chance to log or handle them.
#
# InitialRAMPercentage == MaxRAMPercentage pins the heap at its max size from
# JVM startup, so there's no resize event left to pause on. Percentage-based
# (not a fixed -Xmx like -Xmx2g) so it scales correctly with whatever memory
# limit the container actually gets on Render, rather than a number picked
# for one specific host size. UseContainerSupport is on by default since
# JDK 10 - the percentage is computed against the container's cgroup memory
# limit, not the host's full physical RAM.
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:InitialRAMPercentage=75.0", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=100", \
    "-jar", "/app/app.jar"]
