package payment_infra.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;

    public OutboxPublisher(
            OutboxEventRepository outboxEventRepository,
            EventPublisher eventPublisher) {

        this.outboxEventRepository =
                outboxEventRepository;

        this.eventPublisher =
                eventPublisher;
    }

    // literally a worker.
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPendingEvents() {

        List<OutboxEvent> pendingEvents =
                outboxEventRepository
                        .findTop100ByStatusOrderByCreatedAtAsc(
                                OutboxEventStatus.PENDING
                        );

        for (OutboxEvent event : pendingEvents) {

            eventPublisher.publish(event);

            event.markPublished();

            outboxEventRepository.save(event);
        }
    }
}