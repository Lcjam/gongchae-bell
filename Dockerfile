FROM eclipse-temurin:21-jdk-noble AS builder

WORKDIR /workspace
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY src src

RUN chmod +x gradlew \
    && ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-noble AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && (id -u ubuntu >/dev/null 2>&1 || useradd -u 1000 -m -s /usr/sbin/nologin ubuntu) \
    && mkdir -p /app /var/lib/gongchae-bell/erasure \
    && chown -R 1000:1000 /app /var/lib/gongchae-bell

WORKDIR /app
COPY --from=builder --chown=1000:1000 /workspace/build/libs/gongchae-bell.jar /app/gongchae-bell.jar

ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx512m -XX:+ExitOnOutOfMemoryError"
USER 1000:1000
EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "/app/gongchae-bell.jar"]
