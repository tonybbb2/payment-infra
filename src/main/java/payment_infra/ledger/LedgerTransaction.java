package payment_infra.ledger;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "ledger_transactions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ledger_payment_transaction_type",
                        columnNames = {
                                "payment_id",
                                "transaction_type"
                        }
                )
        }
)
public class LedgerTransaction {

    @Id
    private UUID id;

    @Column(
            name = "payment_id",
            nullable = false
    )
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "transaction_type",
            nullable = false
    )
    private LedgerTransactionType transactionType;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    protected LedgerTransaction() {
    }

    public LedgerTransaction(
            UUID paymentId,
            LedgerTransactionType transactionType) {

        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.transactionType = transactionType;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public LedgerTransactionType getTransactionType() {
        return transactionType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}