package payment_infra.model;

public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    REFUNDED,
    FAILED,
    UNKNOWN
}