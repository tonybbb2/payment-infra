package payment_infra.processor;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FakePaymentProcessor implements PaymentProcessor {

    private final Map<String, ProcessorStatus> transactions =
            new ConcurrentHashMap<>();

    private ProcessorMode mode = ProcessorMode.SUCCESS;

    public void setMode(ProcessorMode mode) {
        this.mode = mode;
    }

    @Override
    public ProcessorResult authorize(
            UUID paymentId,
            BigDecimal amount,
            String currency) {

        if (mode == ProcessorMode.FAILURE) {
            return new ProcessorResult(
                    false,
                    null,
                    "Processor declined authorization"
            );
        }

        if (mode == ProcessorMode.TIMEOUT) {

            String transactionId = "txn_" + UUID.randomUUID();

            transactions.put(
                    transactionId,
                    ProcessorStatus.AUTHORIZED
            );

            throw new ProcessorTimeoutException(
                    "Processor timed out after authorization",
                    transactionId
            );
        }

        String transactionId = "txn_" + UUID.randomUUID();

        transactions.put(
                transactionId,
                ProcessorStatus.AUTHORIZED
        );

        return new ProcessorResult(
                true,
                transactionId,
                "Authorization successful"
        );
    }

    @Override
    public ProcessorResult capture(String transactionId) {

        ProcessorStatus status = transactions.get(transactionId);

        if (status != ProcessorStatus.AUTHORIZED) {
            return new ProcessorResult(
                    false,
                    transactionId,
                    "Transaction cannot be captured"
            );
        }

        transactions.put(
                transactionId,
                ProcessorStatus.CAPTURED
        );

        return new ProcessorResult(
                true,
                transactionId,
                "Capture successful"
        );
    }

    @Override
    public ProcessorResult refund(String transactionId) {

        ProcessorStatus status = transactions.get(transactionId);

        if (status != ProcessorStatus.CAPTURED) {
            return new ProcessorResult(
                    false,
                    transactionId,
                    "Transaction cannot be refunded"
            );
        }

        transactions.put(
                transactionId,
                ProcessorStatus.REFUNDED
        );

        return new ProcessorResult(
                true,
                transactionId,
                "Refund successful"
        );
    }

    @Override
    public ProcessorStatus getStatus(String transactionId) {
        return transactions.getOrDefault(
                transactionId,
                ProcessorStatus.NOT_FOUND
        );
    }
}