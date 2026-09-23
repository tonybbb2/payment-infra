package payment_infra.ledger;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(
            name = "ledger_transaction_id",
            nullable = false
    )
    private UUID ledgerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "account",
            nullable = false
    )
    private LedgerAccount account;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "entry_type",
            nullable = false
    )
    private LedgerEntryType entryType;

    @Column(
            name = "amount",
            nullable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal amount;

    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(
            UUID ledgerTransactionId,
            LedgerAccount account,
            LedgerEntryType entryType,
            BigDecimal amount) {

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Ledger entry amount must be greater than zero"
            );
        }

        this.id = UUID.randomUUID();
        this.ledgerTransactionId = ledgerTransactionId;
        this.account = account;
        this.entryType = entryType;
        this.amount = amount;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public LedgerAccount getAccount() {
        return account;
    }

    public LedgerEntryType getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}