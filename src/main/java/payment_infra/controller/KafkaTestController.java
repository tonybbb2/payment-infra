package payment_infra.controller;

import payment_infra.kafka.KafkaEventPublisher;
import payment_infra.outbox.OutboxEvent;
import payment_infra.outbox.OutboxEventRepository;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/kafka-test")
public class KafkaTestController {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaEventPublisher kafkaEventPublisher;

    public KafkaTestController(
            OutboxEventRepository outboxEventRepository,
            KafkaEventPublisher kafkaEventPublisher) {

        this.outboxEventRepository =
                outboxEventRepository;

        this.kafkaEventPublisher =
                kafkaEventPublisher;
    }

    @PostMapping("/replay/{eventId}")
    public String replayEvent(
            @PathVariable UUID eventId) {

        OutboxEvent event =
                outboxEventRepository
                        .findById(eventId)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Outbox event not found"
                                )
                        );

        kafkaEventPublisher.publish(event);

        return "Replayed event " + eventId;
    }
}