package payment_infra.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class LedgerService {

    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(
            LedgerTransactionRepository ledgerTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository) {

        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public LedgerTransaction postCapture(
            UUID paymentId,
            BigDecimal amount) {

        return postTransaction(
                paymentId,
                LedgerTransactionType.CAPTURE,
                List.of(
                        new LedgerPosting(
                                LedgerAccount.PROCESSOR_CLEARING,
                                LedgerEntryType.DEBIT,
                                amount
                        ),
                        new LedgerPosting(
                                LedgerAccount.MERCHANT_PAYABLE,
                                LedgerEntryType.CREDIT,
                                amount
                        )
                )
        );
    }

    @Transactional
    public LedgerTransaction postRefund(
            UUID paymentId,
            BigDecimal amount) {

        return postTransaction(
                paymentId,
                LedgerTransactionType.REFUND,
                List.of(
                        new LedgerPosting(
                                LedgerAccount.MERCHANT_PAYABLE,
                                LedgerEntryType.DEBIT,
                                amount
                        ),
                        new LedgerPosting(
                                LedgerAccount.PROCESSOR_CLEARING,
                                LedgerEntryType.CREDIT,
                                amount
                        )
                )
        );
    }

    private LedgerTransaction postTransaction(
            UUID paymentId,
            LedgerTransactionType transactionType,
            List<LedgerPosting> postings) {

        LedgerTransaction existingTransaction =
                ledgerTransactionRepository
                        .findByPaymentIdAndTransactionType(
                                paymentId,
                                transactionType
                        )
                        .orElse(null);

        if (existingTransaction != null) {
            return existingTransaction;
        }

        validateBalanced(postings);

        LedgerTransaction ledgerTransaction =
                new LedgerTransaction(
                        paymentId,
                        transactionType
                );

        ledgerTransactionRepository.save(
                ledgerTransaction
        );

        for (LedgerPosting posting : postings) {

            LedgerEntry entry =
                    new LedgerEntry(
                            ledgerTransaction.getId(),
                            posting.account(),
                            posting.entryType(),
                            posting.amount()
                    );

            ledgerEntryRepository.save(entry);
        }

        return ledgerTransaction;
    }

    private void validateBalanced(
            List<LedgerPosting> postings) {

        BigDecimal totalDebits = postings.stream()
                .filter(posting ->
                        posting.entryType()
                                == LedgerEntryType.DEBIT
                )
                .map(LedgerPosting::amount)
                .reduce(
                        BigDecimal.ZERO,
                        BigDecimal::add
                );

        BigDecimal totalCredits = postings.stream()
                .filter(posting ->
                        posting.entryType()
                                == LedgerEntryType.CREDIT
                )
                .map(LedgerPosting::amount)
                .reduce(
                        BigDecimal.ZERO,
                        BigDecimal::add
                );

        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new IllegalStateException(
                    "Ledger transaction is not balanced"
            );
        }
    }

    private record LedgerPosting(
            LedgerAccount account,
            LedgerEntryType entryType,
            BigDecimal amount) {
    }
}