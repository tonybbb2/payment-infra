package payment_infra.outbox;

public enum OutboxEventType {
    PAYMENT_AUTHORIZED,
    PAYMENT_CAPTURED,
    PAYMENT_REFUNDED,
    PAYMENT_FAILED,
    PAYMENT_RECONCILED
}