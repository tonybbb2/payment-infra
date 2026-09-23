package payment_infra.service;

import payment_infra.model.Payment;
import payment_infra.model.PaymentStatus;
import payment_infra.repository.PaymentRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PaymentReconciliationJob {

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    public PaymentReconciliationJob(
            PaymentRepository paymentRepository,
            PaymentService paymentService) {

        this.paymentRepository =
                paymentRepository;

        this.paymentService =
                paymentService;
    }

    @Scheduled(fixedDelay = 10000)
    public void reconcileUnknownPayments() {

        List<Payment> unknownPayments =
                paymentRepository.findByStatus(
                        PaymentStatus.UNKNOWN
                );

        if (unknownPayments.isEmpty()) {
            return;
        }

        System.out.println(
                "Reconciliation job found "
                        + unknownPayments.size()
                        + " UNKNOWN payment(s)"
        );

        for (Payment payment : unknownPayments) {

            try {

                Payment reconciled =
                        paymentService.reconcilePayment(
                                payment.getId()
                        );

                System.out.println(
                        "Reconciled payment:"
                                + " id=" + reconciled.getId()
                                + " status=" + reconciled.getStatus()
                );

            } catch (Exception exception) {

                System.out.println(
                        "Failed to reconcile payment:"
                                + " id=" + payment.getId()
                                + " error=" + exception.getMessage()
                );
            }
        }
    }
}