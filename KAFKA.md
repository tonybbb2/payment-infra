# Kafka in the Payment Infrastructure Project

This document explains why Kafka exists in this project, what happens to a
payment event from beginning to end, and how the reliability mechanisms fit
together.

## Short version

Kafka is the asynchronous event-delivery layer of the application.

The payment API first saves the authoritative payment state in PostgreSQL.
After that transaction commits, Kafka distributes a description of the change
to consumers that may need to react to it.

Examples of events include:

- `PAYMENT_AUTHORIZED`
- `PAYMENT_CAPTURED`
- `PAYMENT_REFUNDED`
- `PAYMENT_FAILED`
- `PAYMENT_RECONCILED`

In a larger system, independent consumers could use these events to send a
receipt, update analytics, detect fraud, notify accounting, or refresh another
service without adding those responsibilities to the payment request itself.

Kafka is not the source of truth for payments in this project. PostgreSQL owns
the payment state, ledger, outbox, and processed-event records. Kafka transports
events between producers and consumers.

## End-to-end flow

```text
Client request
    |
    v
PaymentService
    |
    | One PostgreSQL transaction
    +-- update payment state
    +-- write ledger entries when required
    +-- insert PENDING outbox event
    |
    v
Commit succeeds
    |
    v
OutboxPublisher (every 5 seconds, up to 100 events)
    |
    v
Kafka topic: payment-events
    |
    v
PaymentEventConsumer
    |
    +-- event already in processed_events -> ignore duplicate
    |
    +-- processing succeeds -> insert processed_events row
    |
    +-- processing fails -> retry twice, then send to payment-events.DLT
```

## Why the outbox is necessary

Writing to PostgreSQL and publishing to Kafka are two separate operations. A
naive implementation could update the payment first and then publish directly:

```text
1. Commit payment to PostgreSQL
2. Publish event to Kafka
```

If the application crashes between those steps, the payment exists but its
event is permanently lost. Reversing the order creates the opposite problem:
Kafka could contain an event for a database transaction that later rolls back.

This project uses the transactional outbox pattern instead. `PaymentService`
writes the payment changes and a `PENDING` row in `outbox_events` inside the
same local database transaction. They therefore commit or roll back together.

The outbox row contains:

- a unique event ID;
- the payment ID as the aggregate ID;
- the event type;
- a JSON payment payload;
- `PENDING` or `PUBLISHED` status;
- creation and publication timestamps.

The payload records the payment ID, amount, currency, current status, and
processor transaction ID.

## Publishing to Kafka

`OutboxPublisher` runs every five seconds. Each run loads the oldest 100
`PENDING` events and passes them to `KafkaEventPublisher`.

The publisher converts each outbox row into this Kafka envelope:

```json
{
  "eventId": "unique-event-id",
  "paymentId": "payment-id",
  "eventType": "PAYMENT_CAPTURED",
  "payload": "{...payment data as JSON...}"
}
```

It publishes the JSON to `payment-events` and uses the payment ID as the Kafka
message key. Events for the same payment are consequently routed to the same
partition, where Kafka maintains their partition order.

The call waits for Kafka to acknowledge the send. Only then is the outbox row
marked `PUBLISHED`.

If Kafka is unavailable or rejects the send, the publisher throws an exception
and the surrounding database transaction does not mark the event as published.
The `PENDING` event can be attempted again on a later scheduler run.

## Topics and partitions

The application creates two topics:

| Topic | Partitions | Purpose |
| --- | ---: | --- |
| `payment-events` | 3 | Normal payment lifecycle events |
| `payment-events.DLT` | 3 | Events that still fail after consumer retries |

Both topics use a replication factor of `1` because the local Docker setup runs
one Kafka broker. That is appropriate for development, but it does not provide
broker-level redundancy. A production deployment would normally use multiple
brokers and a higher replication factor.

## At-least-once delivery and duplicates

The design intentionally uses at-least-once delivery. An event should not be
silently lost, but it may be delivered more than once.

For example, Kafka may accept an event and the application may stop before the
outbox row is committed as `PUBLISHED`. After restart, that row is still
`PENDING`, so the publisher sends it again.

Every event has a stable `eventId`. Before processing an event, the consumer
checks the PostgreSQL `processed_events` table:

```text
event ID not present -> process event and save its ID
event ID present     -> duplicate; return without processing it again
```

The consumer method is transactional, so successful processing and insertion
of the processed-event marker belong to the same database transaction. This is
what makes the consumer idempotent for re-delivered event IDs.

This is not global exactly-once delivery. It is at-least-once delivery combined
with application-level deduplication.

## Retries and the dead-letter topic

If the consumer throws an exception, Spring Kafka waits two seconds and retries
the record twice. This produces at most three processing attempts: the original
attempt plus two retries.

After the retries are exhausted, `DeadLetterPublishingRecoverer` sends the
record to the same partition number in `payment-events.DLT`. This isolates a
poison event so it does not permanently block normal consumption.

`DeadLetterConsumer` currently logs dead-letter messages. It does not
automatically repair or replay them. In a production system, this path would
usually also include monitoring, alerting, investigation metadata, and a
controlled replay process.

## What the current consumer does

`PaymentEventConsumer` is deliberately small because this is a reliability
learning project. It:

1. deserializes the Kafka JSON;
2. checks whether the event ID was already processed;
3. optionally throws a simulated failure in the `dev` profile;
4. logs the payment event;
5. stores the event ID in `processed_events`.

The consumer demonstrates the delivery mechanics. It does not yet send emails,
update another service, or perform analytics.

## Relationship with reconciliation

Kafka does not reconcile an ambiguous payment by itself.

The reconciliation job queries the simulated processor for payments in the
`UNKNOWN` state. When reconciliation changes the local payment state, that same
database transaction creates a `PAYMENT_RECONCILED` outbox event. The normal
outbox-to-Kafka flow then distributes the result.

## What Kafka is not doing

Kafka does not:

- authorize, capture, or refund a payment;
- decide which payment-state transitions are valid;
- replace PostgreSQL or the double-entry ledger;
- fix `UNKNOWN` payments;
- make the database and Kafka one distributed transaction;
- prevent duplicate delivery by itself.

Those responsibilities belong to the payment service, PostgreSQL constraints,
the ledger, reconciliation logic, the transactional outbox, and idempotent
consumer behavior.

## Running Kafka locally

Start PostgreSQL and Kafka:

```bash
docker compose up -d
```

Run the application:

```bash
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

Kafka is available to the application at `localhost:9092` by default. Override
it with `KAFKA_BOOTSTRAP_SERVERS` when necessary.

List the topics inside the local Kafka container:

```bash
docker exec payment-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

Read payment events from the beginning:

```bash
docker exec payment-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic payment-events \
  --from-beginning
```

Read dead-letter events:

```bash
docker exec payment-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic payment-events.DLT \
  --from-beginning
```

## Inspecting the outbox

Retrieve pending events:

```http
GET /outbox/pending
```

Retrieve all outbox events for one payment:

```http
GET /outbox/payment/{paymentId}
```

Because the scheduler runs every five seconds, a newly created event may appear
as `PENDING` briefly before becoming `PUBLISHED`.

## Testing duplicate delivery and consumer failure

The following utilities only exist when the Spring `dev` profile is enabled:

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
.\mvnw.cmd spring-boot:run
```

Replay an existing outbox event:

```http
POST /kafka-test/replay/{eventId}
```

If the event was already processed, the consumer should log that the duplicate
was ignored and should not create another `processed_events` row.

Force the consumer to fail:

```http
POST /kafka-consumer/mode/FAILURE
```

The next event will be attempted three times and then published to
`payment-events.DLT`. Restore normal processing with:

```http
POST /kafka-consumer/mode/SUCCESS
```

## Important implementation notes

- `payment-events` and `payment-events.DLT` each have three partitions.
- The local environment has one broker and replication factor `1`.
- The outbox scheduler polls every five seconds and processes up to 100 events.
- The producer waits for Kafka acknowledgement before marking an event as
  published.
- The main consumer group is currently declared directly as
  `payment-event-consumers` on `@KafkaListener`.
- Although `KAFKA_CONSUMER_GROUP` exists in `application.properties`, that
  variable does not currently override the explicitly declared listener group.
- Dead-letter messages are logged but are not automatically replayed.

## Key takeaway

Kafka lets payment changes be distributed asynchronously without coupling the
payment API to every downstream action. The transactional outbox prevents a
committed payment event from disappearing, while stable event IDs,
`processed_events`, retries, and the dead-letter topic make duplicate and
failing deliveries explicit and recoverable.
