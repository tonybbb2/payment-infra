package payment_infra.processor;

public record ProcessorResult(
        boolean success,
        String transactionId,
        String message
) {
}