package payment_infra.reservation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/reservations")
public class ReservationController {
    private final ReservationService service;

    // Connects the HTTP API to the transactional reservation service.
    public ReservationController(ReservationService service) { this.service = service; }

    // Returns 201 for a new reservation or 200 for a matching retry after the transaction commits.
    @PostMapping
    public ResponseEntity<Reservation> create(@RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody CreateReservationRequest request) {
        var result = service.create(request.sku(), request.quantity(), key);
        return result.created()
                ? ResponseEntity.created(URI.create("/reservations/" + result.reservation().getId())).body(result.reservation())
                : ResponseEntity.ok(result.reservation());
    }

    // Looks up an existing allocation by its reservation ID.
    @GetMapping("/{id}")
    public Reservation get(@PathVariable UUID id) { return service.get(id); }

    public record CreateReservationRequest(
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String sku,
            @NotNull @Min(1) Integer quantity) {}
}
