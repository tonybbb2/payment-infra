package payment_infra.controller;

import payment_infra.ledger.LedgerEntry;
import payment_infra.ledger.LedgerEntryRepository;
import payment_infra.ledger.LedgerService;
import payment_infra.ledger.LedgerTransaction;
import payment_infra.ledger.LedgerTransactionRepository;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ledger")
public class LedgerController {

    private final LedgerService ledgerService;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerController(
            LedgerService ledgerService,
            LedgerTransactionRepository ledgerTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository) {

        this.ledgerService = ledgerService;
        this.ledgerTransactionRepository =
                ledgerTransactionRepository;
        this.ledgerEntryRepository =
                ledgerEntryRepository;
    }

    @PostMapping("/capture/{paymentId}")
    public LedgerTransaction postCapture(
            @PathVariable UUID paymentId,
            @RequestParam BigDecimal amount) {

        return ledgerService.postCapture(
                paymentId,
                amount
        );
    }

    @PostMapping("/refund/{paymentId}")
    public LedgerTransaction postRefund(
            @PathVariable UUID paymentId,
            @RequestParam BigDecimal amount) {

        return ledgerService.postRefund(
                paymentId,
                amount
        );
    }

    @GetMapping("/transactions/payment/{paymentId}")
    public List<LedgerTransaction>
    getTransactionsForPayment(
            @PathVariable UUID paymentId) {

        return ledgerTransactionRepository
                .findByPaymentId(paymentId);
    }

    @GetMapping("/entries/{ledgerTransactionId}")
    public List<LedgerEntry> getEntries(
            @PathVariable UUID ledgerTransactionId) {

        return ledgerEntryRepository
                .findByLedgerTransactionId(
                        ledgerTransactionId
                );
    }
}