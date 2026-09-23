package payment_infra.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

@Component
public class PaymentMetrics {

    private final Counter paymentsCreated;
    private final Counter paymentsAuthorized;
    private final Counter paymentsCaptured;
    private final Counter paymentsRefunded;
    private final Counter paymentsUnknown;

    private final Counter reconciliationAttempts;
    private final Counter reconciliationSuccesses;
    private final Counter reconciliationFailures;

    public PaymentMetrics(
            MeterRegistry meterRegistry) {

        this.paymentsCreated =
                Counter.builder("payments.created")
                        .description(
                                "Total number of payments created"
                        )
                        .register(meterRegistry);

        this.paymentsAuthorized =
                Counter.builder("payments.authorized")
                        .description(
                                "Total number of payments authorized"
                        )
                        .register(meterRegistry);

        this.paymentsCaptured =
                Counter.builder("payments.captured")
                        .description(
                                "Total number of payments captured"
                        )
                        .register(meterRegistry);

        this.paymentsRefunded =
                Counter.builder("payments.refunded")
                        .description(
                                "Total number of payments refunded"
                        )
                        .register(meterRegistry);

        this.paymentsUnknown =
                Counter.builder("payments.unknown")
                        .description(
                                "Total number of payments entering UNKNOWN state"
                        )
                        .register(meterRegistry);

        this.reconciliationAttempts =
                Counter.builder(
                                "reconciliation.attempts"
                        )
                        .description(
                                "Total number of reconciliation attempts"
                        )
                        .register(meterRegistry);

        this.reconciliationSuccesses =
                Counter.builder(
                                "reconciliation.successes"
                        )
                        .description(
                                "Total number of successful reconciliations"
                        )
                        .register(meterRegistry);

        this.reconciliationFailures =
                Counter.builder(
                                "reconciliation.failures"
                        )
                        .description(
                                "Total number of failed reconciliation attempts"
                        )
                        .register(meterRegistry);
    }

    public void paymentCreated() {
        paymentsCreated.increment();
    }

    public void paymentAuthorized() {
        paymentsAuthorized.increment();
    }

    public void paymentCaptured() {
        paymentsCaptured.increment();
    }

    public void paymentRefunded() {
        paymentsRefunded.increment();
    }

    public void paymentUnknown() {
        paymentsUnknown.increment();
    }

    public void reconciliationAttempt() {
        reconciliationAttempts.increment();
    }

    public void reconciliationSuccess() {
        reconciliationSuccesses.increment();
    }

    public void reconciliationFailure() {
        reconciliationFailures.increment();
    }
}