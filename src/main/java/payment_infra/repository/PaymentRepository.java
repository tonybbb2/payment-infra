package payment_infra.repository;

import payment_infra.model.Payment;
import payment_infra.model.PaymentStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository
        extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIdempotencyKey(
            String idempotencyKey
    );

    List<Payment> findByStatus(
            PaymentStatus status
    );

    @Modifying
    @Query(
            value = """
                INSERT INTO payments (
                    id,
                    amount,
                    currency,
                    status,
                    idempotency_key,
                    created_at
                )
                VALUES (
                    :id,
                    :amount,
                    :currency,
                    :status,
                    :idempotencyKey,
                    :createdAt
                )
                ON CONFLICT (idempotency_key)
                DO NOTHING
                """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("amount") BigDecimal amount,
            @Param("currency") String currency,
            @Param("status") String status,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("createdAt") Instant createdAt
    );
}