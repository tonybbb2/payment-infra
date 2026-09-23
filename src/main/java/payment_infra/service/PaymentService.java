package payment_infra.service;

import payment_infra.dto.CreatePaymentRequest;
import payment_infra.ledger.LedgerService;
import payment_infra.metrics.PaymentMetrics;
import payment_infra.model.Payment;
import payment_infra.model.PaymentStatus;
import payment_infra.outbox.OutboxEventType;
import payment_infra.outbox.OutboxService;
import payment_infra.processor.PaymentProcessor;
import payment_infra.processor.ProcessorResult;
import payment_infra.processor.ProcessorStatus;
import payment_infra.processor.ProcessorTimeoutException;
import payment_infra.repository.PaymentRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProcessor paymentProcessor;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;
    private final PaymentMetrics paymentMetrics;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentProcessor paymentProcessor,
            LedgerService ledgerService,
            OutboxService outboxService,
            PaymentMetrics paymentMetrics) {

        this.paymentRepository =
                paymentRepository;

        this.paymentProcessor =
                paymentProcessor;

        this.ledgerService =
                ledgerService;

        this.outboxService =
                outboxService;

        this.paymentMetrics =
                paymentMetrics;
    }

    @Transactional
    public Payment createPayment(
            CreatePaymentRequest request,
            String idempotencyKey) {

        Payment existingPayment =
                paymentRepository
                        .findByIdempotencyKey(
                                idempotencyKey
                        )
                        .orElse(null);

        if (existingPayment != null) {

            validateIdempotentRequest(
                    existingPayment,
                    request
            );

            return existingPayment;
        }

        UUID paymentId =
                UUID.randomUUID();

        Instant createdAt =
                Instant.now();

        int inserted =
                paymentRepository.insertIfAbsent(
                        paymentId,
                        request.amount(),
                        request.currency(),
                        PaymentStatus.CREATED.name(),
                        idempotencyKey,
                        createdAt
                );

        if (inserted == 1) {

            Payment createdPayment =
                    paymentRepository
                            .findById(paymentId)
                            .orElseThrow();

            paymentMetrics.paymentCreated();

            return createdPayment;
        }

        Payment concurrentPayment =
                paymentRepository
                        .findByIdempotencyKey(
                                idempotencyKey
                        )
                        .orElseThrow();

        validateIdempotentRequest(
                concurrentPayment,
                request
        );

        return concurrentPayment;
    }

    public Payment getPayment(
            UUID paymentId) {

        return paymentRepository
                .findById(paymentId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Payment not found"
                        )
                );
    }

    @Transactional
    public Payment authorizePayment(
            UUID paymentId) {

        Payment payment =
                getPayment(paymentId);

        try {

            ProcessorResult result =
                    paymentProcessor.authorize(
                            payment.getId(),
                            payment.getAmount(),
                            payment.getCurrency()
                    );

            if (!result.success()) {

                payment.fail();

                Payment saved =
                        paymentRepository.save(
                                payment
                        );

                outboxService.recordPaymentEvent(
                        saved,
                        OutboxEventType.PAYMENT_FAILED
                );

                return saved;
            }

            payment.authorize(
                    result.transactionId()
            );

            Payment saved =
                    paymentRepository.save(
                            payment
                    );

            outboxService.recordPaymentEvent(
                    saved,
                    OutboxEventType.PAYMENT_AUTHORIZED
            );

            paymentMetrics.paymentAuthorized();

            return saved;

        } catch (ProcessorTimeoutException exception) {

            payment.markUnknown(
                    exception.getTransactionId()
            );

            Payment saved =
                    paymentRepository.save(
                            payment
                    );

            paymentMetrics.paymentUnknown();

            return saved;
        }
    }

    @Transactional
    public Payment capturePayment(
            UUID paymentId) {

        Payment payment =
                getPayment(paymentId);

        ProcessorResult result =
                paymentProcessor.capture(
                        payment.getProcessorTransactionId()
                );

        if (!result.success()) {

            payment.fail();

            Payment saved =
                    paymentRepository.save(
                            payment
                    );

            outboxService.recordPaymentEvent(
                    saved,
                    OutboxEventType.PAYMENT_FAILED
            );

            return saved;
        }

        payment.capture();

        ledgerService.postCapture(
                payment.getId(),
                payment.getAmount()
        );

        Payment saved =
                paymentRepository.save(
                        payment
                );

        outboxService.recordPaymentEvent(
                saved,
                OutboxEventType.PAYMENT_CAPTURED
        );

        paymentMetrics.paymentCaptured();

        return saved;
    }

    @Transactional
    public Payment refundPayment(
            UUID paymentId) {

        Payment payment =
                getPayment(paymentId);

        ProcessorResult result =
                paymentProcessor.refund(
                        payment.getProcessorTransactionId()
                );

        if (!result.success()) {

            throw new IllegalStateException(
                    "Processor refund failed: "
                            + result.message()
            );
        }

        payment.refund();

        ledgerService.postRefund(
                payment.getId(),
                payment.getAmount()
        );

        Payment saved =
                paymentRepository.save(
                        payment
                );

        outboxService.recordPaymentEvent(
                saved,
                OutboxEventType.PAYMENT_REFUNDED
        );

        paymentMetrics.paymentRefunded();

        return saved;
    }

    @Transactional
    public Payment reconcilePayment(
            UUID paymentId) {

        Payment payment =
                getPayment(paymentId);

        if (payment.getStatus()
                != PaymentStatus.UNKNOWN) {

            throw new IllegalStateException(
                    "Only UNKNOWN payments can be reconciled"
            );
        }

        ProcessorStatus processorStatus =
                paymentProcessor.getStatus(
                        payment.getProcessorTransactionId()
                );

        switch (processorStatus) {

            case AUTHORIZED -> {

                payment.reconcileAuthorized();
            }

            case FAILED, NOT_FOUND -> {

                payment.reconcileFailed();
            }

            default -> {

                throw new IllegalStateException(
                        "Unexpected processor status during reconciliation: "
                                + processorStatus
                );
            }
        }

        Payment saved =
                paymentRepository.save(
                        payment
                );

        outboxService.recordPaymentEvent(
                saved,
                OutboxEventType.PAYMENT_RECONCILED
        );

        return saved;
    }

    private void validateIdempotentRequest(
            Payment existingPayment,
            CreatePaymentRequest request) {

        boolean sameAmount =
                existingPayment
                        .getAmount()
                        .compareTo(
                                request.amount()
                        ) == 0;

        boolean sameCurrency =
                existingPayment
                        .getCurrency()
                        .equals(
                                request.currency()
                        );

        if (!sameAmount || !sameCurrency) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key already used with a different request"
            );
        }
    }
}