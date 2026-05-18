# ── Build Stage ─────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /build
COPY pom.xml .
COPY ledger-core/pom.xml ledger-core/pom.xml
COPY ledger-dao/pom.xml ledger-dao/pom.xml
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY ledger-restful/pom.xml ledger-restful/pom.xml
COPY ledger-feign/pom.xml ledger-feign/pom.xml

# Download dependencies (cache layer)
RUN mvn dependency:go-offline -pl ledger-restful -am -q || true

COPY . .
RUN mvn package -pl ledger-restful -am -DskipTests -q

# ── Runtime Stage ────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl

RUN addgroup -S ledger && adduser -S ledger -G ledger

RUN mkdir -p /var/lib/ledger/rocksdb /var/lib/ledger/raft /var/log/ledger \
    && chown -R ledger:ledger /var/lib/ledger /var/log/ledger

COPY --from=build /build/ledger-restful/target/ledger-restful-*.jar /app/ledger-restful.jar

USER ledger

EXPOSE 8080 28080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
    CMD curl -sf http://localhost:8080/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=10", \
    "-Xms2g", "-Xmx2g", \
    "-Xlog:gc*:file=/var/log/ledger/gc.log:time,uptime:filecount=10,filesize=50m", \
    "-jar", "/app/ledger-restful.jar"]
