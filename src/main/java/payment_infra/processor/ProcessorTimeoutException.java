package payment_infra.processor;

public class ProcessorTimeoutException extends RuntimeException {

    private final String transactionId;

    public ProcessorTimeoutException(
            String message,
            String transactionId) {

        super(message);
        this.transactionId = transactionId;
    }

    public String getTransactionId() {
        return transactionId;
    }
}