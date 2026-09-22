package payment_infra.processor;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentProcessor {

    ProcessorResult authorize(
            UUID paymentId,
            BigDecimal amount,
            String currency
    );

    ProcessorResult capture(String processorTransactionId);

    ProcessorResult refund(String processorTransactionId);

    ProcessorStatus getStatus(String processorTransactionId);
}