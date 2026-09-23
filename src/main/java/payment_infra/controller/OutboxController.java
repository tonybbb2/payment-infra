package payment_infra.controller;

import payment_infra.outbox.OutboxEvent;
import payment_infra.outbox.OutboxEventRepository;
import payment_infra.outbox.OutboxEventStatus;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/outbox")
public class OutboxController {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxController(
            OutboxEventRepository outboxEventRepository) {

        this.outboxEventRepository =
                outboxEventRepository;
    }

    @GetMapping("/payment/{paymentId}")
    public List<OutboxEvent>
    getPaymentEvents(
            @PathVariable UUID paymentId) {

        return outboxEventRepository
                .findByAggregateId(
                        paymentId
                );
    }

    @GetMapping("/pending")
    public List<OutboxEvent>
    getPendingEvents() {

        return outboxEventRepository
                .findByStatusOrderByCreatedAtAsc(
                        OutboxEventStatus.PENDING
                );
    }
}