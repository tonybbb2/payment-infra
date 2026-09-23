package payment_infra;

import payment_infra.dto.CreatePaymentRequest;
import payment_infra.ledger.LedgerAccount;
import payment_infra.ledger.LedgerEntry;
import payment_infra.ledger.LedgerEntryRepository;
import payment_infra.ledger.LedgerEntryType;
import payment_infra.ledger.LedgerTransaction;
import payment_infra.ledger.LedgerTransactionRepository;
import payment_infra.ledger.LedgerTransactionType;
import payment_infra.model.Payment;
import payment_infra.model.PaymentStatus;
import payment_infra.processor.FakePaymentProcessor;
import payment_infra.processor.ProcessorMode;
import payment_infra.service.PaymentService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class RefundLedgerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("payments_test")
                    .withUsername("payment_user")
                    .withPassword("payment_password");

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry) {

        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );

        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );

        registry.add(
                "spring.kafka.listener.auto-startup",
                () -> "false"
        );
    }

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private FakePaymentProcessor paymentProcessor;

    @Autowired
    private LedgerTransactionRepository ledgerTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void refundCreatesBalancedReversalEntries() {

        paymentProcessor.setMode(
                ProcessorMode.SUCCESS
        );

        BigDecimal amount =
                new BigDecimal("175.00");

        Payment payment =
                paymentService.createPayment(
                        new CreatePaymentRequest(
                                amount,
                                "CAD"
                        ),
                        "refund-ledger-test-001"
                );

        paymentService.authorizePayment(
                payment.getId()
        );

        paymentService.capturePayment(
                payment.getId()
        );

        Payment refunded =
                paymentService.refundPayment(
                        payment.getId()
                );

        assertEquals(
                PaymentStatus.REFUNDED,
                refunded.getStatus()
        );

        LedgerTransaction refundTransaction =
                ledgerTransactionRepository
                        .findByPaymentIdAndTransactionType(
                                payment.getId(),
                                LedgerTransactionType.REFUND
                        )
                        .orElseThrow();

        List<LedgerEntry> entries =
                ledgerEntryRepository
                        .findByLedgerTransactionId(
                                refundTransaction.getId()
                        );

        assertEquals(
                2,
                entries.size()
        );

        LedgerEntry debit =
                entries.stream()
                        .filter(entry ->
                                entry.getEntryType()
                                        == LedgerEntryType.DEBIT
                        )
                        .findFirst()
                        .orElseThrow();

        LedgerEntry credit =
                entries.stream()
                        .filter(entry ->
                                entry.getEntryType()
                                        == LedgerEntryType.CREDIT
                        )
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                LedgerAccount.MERCHANT_PAYABLE,
                debit.getAccount()
        );

        assertEquals(
                LedgerAccount.PROCESSOR_CLEARING,
                credit.getAccount()
        );

        assertEquals(
                0,
                debit.getAmount()
                        .compareTo(amount)
        );

        assertEquals(
                0,
                credit.getAmount()
                        .compareTo(amount)
        );

        assertEquals(
                0,
                debit.getAmount()
                        .compareTo(
                                credit.getAmount()
                        )
        );
    }
}