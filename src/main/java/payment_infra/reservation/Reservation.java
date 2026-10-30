package payment_infra.reservation;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {
    @Id
    private UUID id;
    @Column(nullable = false, length = 100)
    private String sku;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // JPA uses this constructor to load the committed reservation record.
    protected Reservation() {}

    // Returns the durable identity of this stock allocation.
    public UUID getId() { return id; }
    // Identifies the inventory row whose units are held.
    public String getSku() { return sku; }
    // Returns the number of units held by this reservation.
    public int getQuantity() { return quantity; }
    // Identifies the logical request so retries can find the same reservation.
    public String getIdempotencyKey() { return idempotencyKey; }
    // Returns RESERVED; later milestones will add cancellation and expiration transitions.
    public String getStatus() { return status; }
    // Returns when this reservation was first created, unchanged by retries.
    public Instant getCreatedAt() { return createdAt; }
}
