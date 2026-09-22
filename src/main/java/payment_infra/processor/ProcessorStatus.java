package payment_infra.processor;

public enum ProcessorStatus {
    AUTHORIZED,
    CAPTURED,
    REFUNDED,
    FAILED,
    NOT_FOUND
}