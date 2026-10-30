package payment_infra.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    // Finds a previous successful request so a retry can reuse its allocation.
    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    // Lets the unique constraint choose one winner when requests race with the same key.
    @Modifying
    @Query(value = """
            INSERT INTO reservations (id, sku, quantity, idempotency_key, status, created_at)
            VALUES (:id, :sku, :quantity, :key, 'RESERVED', :createdAt)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("sku") String sku,
                       @Param("quantity") int quantity, @Param("key") String key,
                       @Param("createdAt") Instant createdAt);
}
