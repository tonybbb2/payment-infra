package payment_infra.reservation;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import payment_infra.inventory.InventoryService;
import java.time.Instant;
import java.util.UUID;

@Service
public class ReservationService {
    private final ReservationRepository repository;
    private final InventoryService inventory;

    // Shares the existing inventory operation instead of duplicating the stock deduction SQL.
    public ReservationService(ReservationRepository repository, InventoryService inventory) {
        this.repository = repository;
        this.inventory = inventory;
    }

    // Commits the reservation and deduction together; retries reuse the original allocation.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreationResult create(String sku, int quantity, String key) {
        validate(sku, quantity, key);
        Reservation existing = repository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) return replay(existing, sku, quantity);

        // This read distinguishes a missing SKU; it does not decide whether stock is available.
        inventory.get(sku);
        UUID id = UUID.randomUUID();
        int inserted = repository.insertIfAbsent(id, sku, quantity, key, Instant.now());
        if (inserted == 0) {
            // At Read Committed, this new query sees the competing request's committed row.
            return replay(repository.findByIdempotencyKey(key).orElseThrow(), sku, quantity);
        }

        // The inventory service joins this transaction; an exception rolls back both writes.
        inventory.decrement(sku, quantity);
        return new CreationResult(repository.findById(id).orElseThrow(), true);
    }

    // Retrieves a durable reservation without allocating stock again.
    @Transactional(readOnly = true)
    public Reservation get(UUID id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found"));
    }

    // Rejects reuse of a key for different data and returns matching retries without a deduction.
    private CreationResult replay(Reservation existing, String sku, int quantity) {
        if (!existing.getSku().equals(sku) || existing.getQuantity() != quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Idempotency key already used with a different reservation request");
        }
        return new CreationResult(existing, false);
    }

    // Protects service callers as well as HTTP callers from invalid allocation requests.
    private void validate(String sku, int quantity, String key) {
        if (sku == null || !sku.matches("[A-Za-z0-9_-]{1,100}") || quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid SKU and positive quantity required");
        }
        if (key == null || key.isBlank() || key.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must contain 1-255 characters");
        }
    }

    public record CreationResult(Reservation reservation, boolean created) {}
}
