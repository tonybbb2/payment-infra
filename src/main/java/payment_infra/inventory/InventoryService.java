package payment_infra.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryService {
    private final InventoryRepository repository;

    // Receives the repository used for all stock reads and writes.
    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    // Creates initial stock without allowing a repeated request to overwrite existing inventory.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Inventory create(String sku, int quantity) {
        validateSku(sku);
        if (quantity < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Initial quantity cannot be negative");
        }
        if (repository.insertIfAbsent(sku, quantity) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU already exists");
        }
        return get(sku);
    }

    // Loads a stock snapshot or reports that the SKU does not exist.
    @Transactional(readOnly = true)
    public Inventory get(String sku) {
        validateSku(sku);
        return repository.findById(sku).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU not found"));
    }

    // Acquires the full quantity atomically; no payment or durable reservation is created yet.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void decrement(String sku, int quantity) {
        validateSku(sku);
        if (quantity <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive");
        }
        // The affected-row count makes the decision; a previous availability read would be stale.
        if (repository.tryDecrement(sku, quantity) == 1) {
            return;
        }
        if (!repository.existsById(sku)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SKU not found");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient inventory");
    }

    // Keeps service callers subject to the same SKU rules as HTTP callers.
    private void validateSku(String sku) {
        if (sku == null || !sku.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SKU must contain 1-100 letters, digits, underscores, or hyphens");
        }
    }
}
