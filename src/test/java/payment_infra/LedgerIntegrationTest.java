package payment_infra;

import payment_infra.dto.CreatePaymentRequest;
import payment_infra.ledger.LedgerEntry;
import payment_infra.ledger.LedgerEntryRepository;
import payment_infra.ledger.LedgerEntryType;
import payment_infra.ledger.LedgerTransaction;
import payment_infra.ledger.LedgerTransactionRepository;
import payment_infra.ledger.LedgerTransactionType;
import payment_infra.model.Payment;
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
class LedgerIntegrationTest {

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
    void successfulCaptureCreatesBalancedLedgerEntries() {

        paymentProcessor.setMode(
                ProcessorMode.SUCCESS
        );

        CreatePaymentRequest request =
                new CreatePaymentRequest(
                        new BigDecimal("150.00"),
                        "CAD"
                );

        Payment payment =
                paymentService.createPayment(
                        request,
                        "ledger-test-001"
                );

        Payment authorized =
                paymentService.authorizePayment(
                        payment.getId()
                );

        assertEquals(
                "AUTHORIZED",
                authorized.getStatus().name()
        );

        Payment captured =
                paymentService.capturePayment(
                        payment.getId()
                );

        assertEquals(
                "CAPTURED",
                captured.getStatus().name()
        );

        LedgerTransaction ledgerTransaction =
                ledgerTransactionRepository
                        .findByPaymentIdAndTransactionType(
                                payment.getId(),
                                LedgerTransactionType.CAPTURE
                        )
                        .orElseThrow();

        List<LedgerEntry> entries =
                ledgerEntryRepository
                        .findByLedgerTransactionId(
                                ledgerTransaction.getId()
                        );

        assertEquals(
                2,
                entries.size()
        );

        BigDecimal debitTotal =
                entries.stream()
                        .filter(entry ->
                                entry.getEntryType()
                                        == LedgerEntryType.DEBIT
                        )
                        .map(LedgerEntry::getAmount)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        BigDecimal creditTotal =
                entries.stream()
                        .filter(entry ->
                                entry.getEntryType()
                                        == LedgerEntryType.CREDIT
                        )
                        .map(LedgerEntry::getAmount)
                        .reduce(
                                BigDecimal.ZERO,
                                BigDecimal::add
                        );

        assertEquals(
                0,
                debitTotal.compareTo(creditTotal)
        );

        assertEquals(
                0,
                debitTotal.compareTo(
                        new BigDecimal("150.00")
                )
        );
    }
}