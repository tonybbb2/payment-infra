package payment_infra;

import payment_infra.dto.CreatePaymentRequest;
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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest
class ReconciliationIntegrationTest {

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

    @Test
    void timeoutBecomesUnknownAndReconciliationRecoversPayment() {

        paymentProcessor.setMode(
                ProcessorMode.TIMEOUT
        );

        CreatePaymentRequest request =
                new CreatePaymentRequest(
                        new BigDecimal("200.00"),
                        "CAD"
                );

        Payment payment =
                paymentService.createPayment(
                        request,
                        "reconciliation-test-001"
                );

        Payment unknown =
                paymentService.authorizePayment(
                        payment.getId()
                );

        assertEquals(
                PaymentStatus.UNKNOWN,
                unknown.getStatus()
        );

        assertNotNull(
                unknown.getProcessorTransactionId()
        );

        Payment reconciled =
                paymentService.reconcilePayment(
                        payment.getId()
                );

        assertEquals(
                PaymentStatus.AUTHORIZED,
                reconciled.getStatus()
        );

        assertEquals(
                unknown.getProcessorTransactionId(),
                reconciled.getProcessorTransactionId()
        );
    }
}