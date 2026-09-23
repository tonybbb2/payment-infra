package payment_infra.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository
        extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByAggregateId(
            UUID aggregateId
    );

    List<OutboxEvent>
    findByStatusOrderByCreatedAtAsc(
            OutboxEventStatus status
    );

    List<OutboxEvent>
    findTop100ByStatusOrderByCreatedAtAsc(
            OutboxEventStatus status
    );
}