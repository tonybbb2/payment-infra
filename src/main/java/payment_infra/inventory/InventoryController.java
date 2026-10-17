package payment_infra.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/inventory")
public class InventoryController {
    private final InventoryService service;

    // Connects the inventory HTTP endpoints to the stock service.
    public InventoryController(InventoryService service) {
        this.service = service;
    }

    // Creates a new stock row with an initial quantity, returning 409 if the SKU already exists.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Inventory create(@Valid @RequestBody CreateInventoryRequest request) {
        return service.create(request.sku(), request.quantity());
    }

    // Shows current stock; this response does not promise that stock will remain available.
    @GetMapping("/{sku}")
    public Inventory get(@PathVariable String sku) {
        return service.get(sku);
    }

    // Runs the stock-deduction experiment and reports success only after the service transaction commits.
    @PostMapping("/{sku}/decrement")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decrement(@PathVariable String sku, @Valid @RequestBody DecrementInventoryRequest request) {
        service.decrement(sku, request.quantity());
    }

    public record CreateInventoryRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String sku,
            @NotNull @Min(0) Integer quantity) {
    }

    public record DecrementInventoryRequest(@NotNull @Min(1) Integer quantity) {
    }
}
