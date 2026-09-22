package payment_infra.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_idempotency_key", columnNames = "idempotency_key")
})
public class Payment {

    @Id
    private UUID id;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(name = "processor_transaction_id")
    private String processorTransactionId;

    protected Payment() {
    }

    public Payment(
            BigDecimal amount,
            String currency,
            String idempotencyKey) {

        this.id = UUID.randomUUID();
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.CREATED;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getProcessorTransactionId() {
        return processorTransactionId;
    }

    public void authorize(String processorTransactionId) {
        if (this.status != PaymentStatus.CREATED) {
            throw new IllegalStateException(
                    "Only CREATED payments can be authorized"
            );
        }

        this.processorTransactionId = processorTransactionId;
        this.status = PaymentStatus.AUTHORIZED;
    }

    public void capture() {
        if (this.status != PaymentStatus.AUTHORIZED) {
            throw new IllegalStateException(
                    "Only AUTHORIZED payments can be captured");
        }

        this.status = PaymentStatus.CAPTURED;
    }

    public void refund() {
        if (this.status != PaymentStatus.CAPTURED) {
            throw new IllegalStateException(
                    "Only CAPTURED payments can be refunded");
        }

        this.status = PaymentStatus.REFUNDED;
    }

    public void fail() {
        if (this.status == PaymentStatus.CAPTURED
                || this.status == PaymentStatus.REFUNDED) {
            throw new IllegalStateException(
                    "Completed payments cannot be marked as failed");
        }

        this.status = PaymentStatus.FAILED;
    }
}