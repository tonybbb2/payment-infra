package payment_infra.processor;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class FakePaymentProcessor implements PaymentProcessor {

    @Override
    public String authorize(
            UUID paymentId,
            BigDecimal amount,
            String currency) {

        return "txn_" + UUID.randomUUID();
    }

    @Override
    public void capture(String processorTransactionId) {
        // simulated success
    }

    @Override
    public void refund(String processorTransactionId) {
        // simulated success
    }
}