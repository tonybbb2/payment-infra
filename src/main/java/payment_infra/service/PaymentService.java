package payment_infra.service;

import payment_infra.dto.CreatePaymentRequest;
import payment_infra.ledger.LedgerService;
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

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentProcessor paymentProcessor,
            LedgerService ledgerService,
            OutboxService outboxService) {

        this.paymentRepository =
                paymentRepository;

        this.paymentProcessor =
                paymentProcessor;

        this.ledgerService =
                ledgerService;

        this.outboxService =
                outboxService;
    }

    @Transactional
    public Payment createPayment(
            CreatePaymentRequest request,
            String idempotencyKey) {

        String currency =
                request.currency().toUpperCase();

        UUID newPaymentId =
                UUID.randomUUID();

        paymentRepository.insertIfAbsent(
                newPaymentId,
                request.amount(),
                currency,
                "CREATED",
                idempotencyKey,
                Instant.now()
        );

        Payment payment =
                paymentRepository
                        .findByIdempotencyKey(
                                idempotencyKey
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Payment was not created or found"
                                )
                        );

        if (payment.getAmount()
                        .compareTo(
                                request.amount()
                        ) != 0
                || !payment.getCurrency()
                        .equals(currency)) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different payment request"
            );
        }

        return payment;
    }

    public Payment getPayment(UUID id) {

        return paymentRepository
                .findById(id)
                .orElseThrow(() ->
                        new RuntimeException(
                                "Payment not found"
                        )
                );
    }

    @Transactional
    public Payment authorizePayment(UUID id) {

        Payment payment =
                paymentRepository
                        .findById(id)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Payment not found"
                                )
                        );

        try {

            ProcessorResult result =
                    paymentProcessor.authorize(
                            payment.getId(),
                            payment.getAmount(),
                            payment.getCurrency()
                    );

            if (!result.success()) {

                payment.fail();

                paymentRepository.save(
                        payment
                );

                outboxService.recordPaymentEvent(
                        payment,
                        OutboxEventType.PAYMENT_FAILED
                );

                return payment;
            }

            payment.authorize(
                    result.transactionId()
            );

            paymentRepository.save(
                    payment
            );

            outboxService.recordPaymentEvent(
                    payment,
                    OutboxEventType.PAYMENT_AUTHORIZED
            );

            return payment;

        } catch (ProcessorTimeoutException exception) {

            payment.markUnknown(
                    exception.getTransactionId()
            );

            return paymentRepository.save(
                    payment
            );
        }
    }

    @Transactional
    public Payment capturePayment(UUID id) {

        Payment payment =
                paymentRepository
                        .findById(id)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Payment not found"
                                )
                        );

        ProcessorResult result =
                paymentProcessor.capture(
                        payment.getProcessorTransactionId()
                );

        if (!result.success()) {

            payment.fail();

            paymentRepository.save(
                    payment
            );

            outboxService.recordPaymentEvent(
                    payment,
                    OutboxEventType.PAYMENT_FAILED
            );

            return payment;
        }

        payment.capture();

        ledgerService.postCapture(
                payment.getId(),
                payment.getAmount()
        );

        paymentRepository.save(
                payment
        );

        outboxService.recordPaymentEvent(
                payment,
                OutboxEventType.PAYMENT_CAPTURED
        );

        return payment;
    }

    @Transactional
    public Payment refundPayment(UUID id) {

        Payment payment =
                paymentRepository
                        .findById(id)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Payment not found"
                                )
                        );

        ProcessorResult result =
                paymentProcessor.refund(
                        payment.getProcessorTransactionId()
                );

        if (!result.success()) {

            throw new IllegalStateException(
                    result.message()
            );
        }

        payment.refund();

        ledgerService.postRefund(
                payment.getId(),
                payment.getAmount()
        );

        paymentRepository.save(
                payment
        );

        outboxService.recordPaymentEvent(
                payment,
                OutboxEventType.PAYMENT_REFUNDED
        );

        return payment;
    }

    @Transactional
    public Payment reconcilePayment(UUID id) {

        Payment payment =
                paymentRepository
                        .findById(id)
                        .orElseThrow(() ->
                                new RuntimeException(
                                        "Payment not found"
                                )
                        );

        if (payment.getStatus()
                != PaymentStatus.UNKNOWN) {

            return payment;
        }

        ProcessorStatus processorStatus =
                paymentProcessor.getStatus(
                        payment.getProcessorTransactionId()
                );

        switch (processorStatus) {

            case AUTHORIZED ->
                    payment.reconcileAuthorized();

            case FAILED, NOT_FOUND ->
                    payment.reconcileFailed();

            default ->
                    throw new IllegalStateException(
                            "Unexpected processor state during reconciliation"
                    );
        }

        paymentRepository.save(
                payment
        );

        outboxService.recordPaymentEvent(
                payment,
                OutboxEventType.PAYMENT_RECONCILED
        );

        return payment;
    }
}