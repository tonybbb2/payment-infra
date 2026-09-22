package payment_infra.processor;

import java.math.BigDecimal;
import java.util.UUID;

// To represent an actual processor like Stripe/Moneris, etc. 
public interface PaymentProcessor {

    String authorize(
            UUID paymentId,
            BigDecimal amount,
            String currency
    );

    void capture(String processorTransactionId);

    void refund(String processorTransactionId);
}