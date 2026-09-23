package payment_infra.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository
        extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction>
    findByPaymentIdAndTransactionType(
            UUID paymentId,
            LedgerTransactionType transactionType
    );

    List<LedgerTransaction>
    findByPaymentId(UUID paymentId);
}