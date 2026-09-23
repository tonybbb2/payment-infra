package payment_infra.outbox;

import org.springframework.stereotype.Component;

@Component
public class LoggingEventPublisher
        implements EventPublisher {

    @Override
    public void publish(OutboxEvent event) {

        System.out.println(
                "Publishing event:"
                        + " id=" + event.getId()
                        + " type=" + event.getEventType()
                        + " aggregateId=" + event.getAggregateId()
                        + " payload=" + event.getPayload()
        );
    }
}