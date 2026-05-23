# ── Build Stage ─────────────────────────────────────────────
FROM maven:3.9-amazoncorretto-21 AS build

WORKDIR /build
COPY pom.xml .
COPY ledger-core/pom.xml ledger-core/pom.xml
COPY ledger-dao/pom.xml ledger-dao/pom.xml
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY ledger-restful/pom.xml ledger-restful/pom.xml
COPY ledger-feign/pom.xml ledger-feign/pom.xml

RUN mvn dependency:go-offline -pl ledger-restful -am -q || true

COPY . .
RUN mvn package -pl ledger-restful -am -Dmaven.test.skip=true -q

# ── Runtime Stage (non-Alpine for RocksDB glibc compat) ─────
FROM amazoncorretto:21

RUN yum install -y shadow-utils curl && yum clean all

RUN groupadd -r ledger && useradd -r -g ledger ledger

RUN mkdir -p /var/lib/ledger/rocksdb /var/lib/ledger/raft /var/log/ledger \
    && chown -R ledger:ledger /var/lib/ledger /var/log/ledger

COPY --from=build /build/ledger-restful/target/ledger-restful-*.jar /app/ledger-restful.jar

USER ledger

EXPOSE 8080 28080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
    CMD curl -sf http://localhost:8080/health || exit 1

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-XX:MaxGCPauseMillis=1", \
    "-Xms2g", "-Xmx2g", \
    "-Xlog:gc*:file=/var/log/ledger/gc.log:time,uptime:filecount=10,filesize=50m", \
    "-Dmanagement.endpoints.web.exposure.include=health,prometheus,metrics,info", \
    "-Dmanagement.metrics.export.prometheus.enabled=true", \
    "-Dserver.shutdown.grace-period=30s", \
    "-jar", "/app/ledger-restful.jar"]
