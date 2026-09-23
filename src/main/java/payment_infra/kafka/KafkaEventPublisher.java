package payment_infra.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import payment_infra.outbox.EventPublisher;
import payment_infra.outbox.OutboxEvent;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventPublisher
        implements EventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate) {

        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void publish(OutboxEvent event) {

        KafkaPaymentEvent kafkaEvent =
                new KafkaPaymentEvent(
                        event.getId(),
                        event.getAggregateId(),
                        event.getEventType().name(),
                        event.getPayload()
                );

        String message;

        try {

            message =
                    objectMapper.writeValueAsString(
                            kafkaEvent
                    );

        } catch (JsonProcessingException exception) {

            throw new IllegalStateException(
                    "Failed to serialize Kafka event",
                    exception
            );
        }

        String key =
                event.getAggregateId().toString();

        kafkaTemplate.send(
                KafkaTopicConfig.PAYMENT_EVENTS_TOPIC,
                key,
                message
        ).join();

        System.out.println(
                "Published event to Kafka:"
                        + " eventId=" + event.getId()
                        + " type=" + event.getEventType()
                        + " paymentId=" + event.getAggregateId()
        );
    }
}