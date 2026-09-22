package payment_infra.service;

import payment_infra.dto.CreatePaymentRequest;
import payment_infra.model.Payment;
import payment_infra.processor.PaymentProcessor;
import payment_infra.repository.PaymentRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProcessor paymentProcessor;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentProcessor paymentProcessor) {

        this.paymentRepository = paymentRepository;
        this.paymentProcessor = paymentProcessor;
    }

    @Transactional
    public Payment createPayment(
            CreatePaymentRequest request,
            String idempotencyKey) {

        String currency = request.currency().toUpperCase();

        UUID newPaymentId = UUID.randomUUID();

        paymentRepository.insertIfAbsent(
                newPaymentId,
                request.amount(),
                currency,
                "CREATED",
                idempotencyKey,
                Instant.now()
        );

        Payment payment = paymentRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Payment was not created or found"
                        )
                );

        // Same idempotency key should represent the same operation
        if (payment.getAmount().compareTo(request.amount()) != 0
                || !payment.getCurrency().equals(currency)) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Idempotency key was already used with a different payment request"
            );
        }

        return payment;
    }

    public Payment getPayment(UUID id) {
        return paymentRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Payment not found")
                );
    }

    @Transactional
    public Payment authorizePayment(UUID id) {

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Payment not found")
                );

        String processorTransactionId =
                paymentProcessor.authorize(
                        payment.getId(),
                        payment.getAmount(),
                        payment.getCurrency()
                );

        payment.authorize(processorTransactionId);

        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment capturePayment(UUID id) {

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Payment not found")
                );

        paymentProcessor.capture(
                payment.getProcessorTransactionId()
        );

        payment.capture();

        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment refundPayment(UUID id) {

        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Payment not found")
                );

        paymentProcessor.refund(
                payment.getProcessorTransactionId()
        );

        payment.refund();

        return paymentRepository.save(payment);
    }
}