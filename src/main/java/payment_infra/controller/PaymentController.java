package payment_infra.controller;

import jakarta.validation.Valid;

import payment_infra.dto.CreatePaymentRequest;
import payment_infra.model.Payment;
import payment_infra.service.PaymentService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Payment createPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        return paymentService.createPayment(
                request,
                idempotencyKey
        );
    }

    @GetMapping("/{id}")
    public Payment getPayment(@PathVariable UUID id) {
        return paymentService.getPayment(id);
    }

    @PostMapping("/{id}/authorize")
    public Payment authorize(@PathVariable UUID id) {
        return paymentService.authorizePayment(id);
    }

    @PostMapping("/{id}/capture")
    public Payment capture(@PathVariable UUID id) {
        return paymentService.capturePayment(id);
    }

    @PostMapping("/{id}/refund")
    public Payment refund(@PathVariable UUID id) {
        return paymentService.refundPayment(id);
    }
}