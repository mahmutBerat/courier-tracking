#!/usr/bin/env sh
# Produces key|json lines from FILE to the courier-locations topic through the Compose Kafka broker.
# Runs inside the broker container on its internal listener, so it works whatever KAFKA_PORT is on the host.
set -eu

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 FILE.jsonl" >&2
  exit 1
fi

docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka:29092 \
  --topic courier-locations \
  --reader-property parse.key=true \
  --reader-property key.separator='|' < "$1"

echo "produced $(grep -c . "$1") message(s) from $1"
