package payment_infra.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class PaymentEventConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(
            ProcessedEventRepository processedEventRepository) {

        this.processedEventRepository =
                processedEventRepository;

        this.objectMapper =
                new ObjectMapper();
    }

    @KafkaListener(
            topics = KafkaTopicConfig.PAYMENT_EVENTS_TOPIC,
            groupId = "payment-event-consumers"
    )
    @Transactional
    public void consume(String message) {

        KafkaPaymentEvent event;

        try {

            event =
                    objectMapper.readValue(
                            message,
                            KafkaPaymentEvent.class
                    );

        } catch (JsonProcessingException exception) {

            throw new IllegalStateException(
                    "Failed to deserialize Kafka event",
                    exception
            );
        }

        UUID eventId =
                event.eventId();

        if (processedEventRepository.existsById(eventId)) {

            System.out.println(
                    "Duplicate Kafka event ignored:"
                            + " eventId=" + eventId
            );

            return;
        }

        System.out.println(
                "Processing Kafka event:"
                        + " eventId=" + eventId
                        + " paymentId=" + event.paymentId()
                        + " type=" + event.eventType()
                        + " payload=" + event.payload()
        );

        processedEventRepository.save(
                new ProcessedEvent(eventId)
        );
    }
}