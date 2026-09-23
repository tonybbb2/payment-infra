package payment_infra;

import payment_infra.dto.CreatePaymentRequest;
import payment_infra.model.Payment;
import payment_infra.outbox.OutboxEvent;
import payment_infra.outbox.OutboxEventRepository;
import payment_infra.outbox.OutboxEventType;
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
class CaptureOutboxIntegrationTest {

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
    private OutboxEventRepository outboxEventRepository;

    @Test
    void captureCreatesPaymentCapturedOutboxEvent() {

        paymentProcessor.setMode(
                ProcessorMode.SUCCESS
        );

        Payment payment =
                paymentService.createPayment(
                        new CreatePaymentRequest(
                                new BigDecimal("250.00"),
                                "CAD"
                        ),
                        "capture-outbox-001"
                );

        paymentService.authorizePayment(
                payment.getId()
        );

        paymentService.capturePayment(
                payment.getId()
        );

        List<OutboxEvent> events =
                outboxEventRepository
                        .findByAggregateId(
                                payment.getId()
                        );

        assertTrue(
                events.stream()
                        .anyMatch(event ->
                                event.getEventType()
                                        == OutboxEventType.PAYMENT_CAPTURED
                        )
        );
    }
}