package payment_infra.controller;

import payment_infra.kafka.ConsumerMode;
import payment_infra.kafka.PaymentEventConsumer;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kafka-consumer")
public class KafkaConsumerTestController {

    private final PaymentEventConsumer paymentEventConsumer;

    public KafkaConsumerTestController(
            PaymentEventConsumer paymentEventConsumer) {

        this.paymentEventConsumer =
                paymentEventConsumer;
    }

    @PostMapping("/mode/{mode}")
    public String setMode(
            @PathVariable ConsumerMode mode) {

        paymentEventConsumer.setMode(mode);

        return "Kafka consumer mode set to " + mode;
    }
}