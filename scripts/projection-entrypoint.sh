#!/bin/sh
exec java \
  ${JAVA_OPTS} \
  -XX:OnOutOfMemoryError='/app/oom-handler.sh %p' \
  -jar /app/projection.jar
