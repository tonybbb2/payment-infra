package payment_infra.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import payment_infra.model.Payment;

import org.springframework.stereotype.Service;

@Service
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(
            OutboxEventRepository outboxEventRepository) {

        this.outboxEventRepository =
                outboxEventRepository;

        this.objectMapper =
                new ObjectMapper();
    }

    public OutboxEvent recordPaymentEvent(
            Payment payment,
            OutboxEventType eventType) {

        PaymentEventPayload payload =
                new PaymentEventPayload(
                        payment.getId(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        payment.getStatus().name(),
                        payment.getProcessorTransactionId()
                );

        String payloadJson;

        try {

            payloadJson =
                    objectMapper.writeValueAsString(
                            payload
                    );

        } catch (JsonProcessingException exception) {

            throw new IllegalStateException(
                    "Failed to serialize payment event",
                    exception
            );
        }

        OutboxEvent outboxEvent =
                new OutboxEvent(
                        "PAYMENT",
                        payment.getId(),
                        eventType,
                        payloadJson
                );

        return outboxEventRepository.save(
                outboxEvent
        );
    }
}