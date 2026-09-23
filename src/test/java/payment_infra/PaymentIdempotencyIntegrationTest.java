package payment_infra;

import payment_infra.dto.CreatePaymentRequest;
import payment_infra.model.Payment;
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
class PaymentIdempotencyIntegrationTest {

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

    @Test
    void sameIdempotencyKeyReturnsSamePayment() {

        String idempotencyKey =
                "integration-test-idempotency-001";

        CreatePaymentRequest request =
                new CreatePaymentRequest(
                        new BigDecimal("100.00"),
                        "CAD"
                );

        Payment first =
                paymentService.createPayment(
                        request,
                        idempotencyKey
                );

        Payment second =
                paymentService.createPayment(
                        request,
                        idempotencyKey
                );

        assertEquals(
                first.getId(),
                second.getId()
        );

        assertEquals(
                first.getIdempotencyKey(),
                second.getIdempotencyKey()
        );

        assertEquals(
                0,
                first.getAmount()
                        .compareTo(second.getAmount())
        );

        assertEquals(
                first.getCurrency(),
                second.getCurrency()
        );
    }
}