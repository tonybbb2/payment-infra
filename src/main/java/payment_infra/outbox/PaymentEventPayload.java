package payment_infra.outbox;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentEventPayload(
        UUID paymentId,
        BigDecimal amount,
        String currency,
        String status,
        String processorTransactionId
) {
}