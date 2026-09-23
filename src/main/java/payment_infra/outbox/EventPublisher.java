package payment_infra.outbox;

public interface EventPublisher {

    void publish(OutboxEvent event);
}