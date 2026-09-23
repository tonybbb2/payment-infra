package payment_infra.kafka;

import org.apache.kafka.clients.admin.NewTopic;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTopicConfig {

    public static final String PAYMENT_EVENTS_TOPIC =
            "payment-events";

    @Bean
    public NewTopic paymentEventsTopic() {

        return new NewTopic(
                PAYMENT_EVENTS_TOPIC,
                3,
                (short) 1
        );
    }
}