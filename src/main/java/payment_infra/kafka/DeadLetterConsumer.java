package payment_infra.kafka;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterConsumer {

    @KafkaListener(
            topics = KafkaTopicConfig.PAYMENT_EVENTS_DLT,
            groupId = "payment-dlt-consumers"
    )
    public void consumeDeadLetter(String message) {

        System.out.println(
                "DEAD LETTER EVENT RECEIVED:"
                        + " payload=" + message
        );
    }
}