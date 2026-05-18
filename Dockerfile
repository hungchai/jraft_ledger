FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S ledger && adduser -S ledger -G ledger

RUN mkdir -p /var/lib/ledger/rocksdb /var/lib/ledger/raft /var/log/ledger \
    && chown -R ledger:ledger /var/lib/ledger /var/log/ledger

COPY ledger-restful/target/ledger-restful-*.jar /app/ledger-restful.jar

USER ledger

EXPOSE 8080

ENTRYPOINT ["java", \
    "-XX:+UseZGC", \
    "-Xms2g", "-Xmx2g", \
    "-XX:MaxGCPauseMillis=1", \
    "-Xlog:gc*:file=/var/log/ledger/gc.log:time,uptime:filecount=10,filesize=50m", \
    "-jar", "/app/ledger-restful.jar"]
