package payment_infra.kafka;

import java.util.UUID;

public record KafkaPaymentEvent(
        UUID eventId,
        UUID paymentId,
        String eventType,
        String payload
) {
}