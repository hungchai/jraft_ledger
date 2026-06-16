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

# jemalloc: glibc malloc retains freed memory under RocksDB's heavy multithreaded
# malloc/free churn — native RSS climbs to GBs and never returns, even at idle
# after a forced GC, driving the cgroup toward OOM independently of the JVM heap,
# direct memory, and RocksDB block cache (all already bounded). jemalloc returns
# memory aggressively and fragments far less, bounding native RSS to the working
# set. Preloaded via LD_PRELOAD below. (RocksDB's FAQ recommends jemalloc/tcmalloc.)
RUN amazon-linux-extras install epel -y && yum install -y jemalloc && yum clean all

RUN groupadd -r ledger && useradd -r -g ledger ledger

RUN mkdir -p /var/lib/ledger/rocksdb /var/lib/ledger/raft /var/log/ledger \
    && chown -R ledger:ledger /var/lib/ledger /var/log/ledger

COPY scripts/ledger-oom-handler.sh /app/oom-handler.sh
COPY scripts/ledger-entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/oom-handler.sh /app/entrypoint.sh \
    && chown ledger:ledger /app/oom-handler.sh /app/entrypoint.sh

COPY --from=build /build/ledger-restful/target/ledger-restful-*.jar /app/ledger-restful.jar

USER ledger

EXPOSE 8080 28080

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
    CMD curl -sf http://localhost:8080/health || exit 1

ENV JAVA_OPTS=""
# Preload jemalloc for the JVM (and its RocksDB JNI). lg_dirty_mult bounds retained
# dirty pages so freed native memory returns to the OS promptly under write churn.
ENV LD_PRELOAD=/usr/lib64/libjemalloc.so.1
ENV MALLOC_CONF=lg_dirty_mult:5
ENTRYPOINT ["/app/entrypoint.sh"]
