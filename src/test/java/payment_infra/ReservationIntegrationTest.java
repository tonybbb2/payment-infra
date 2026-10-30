package payment_infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import payment_infra.inventory.InventoryService;
import payment_infra.reservation.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = ReservationIntegrationTest.LabConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.admin.auto-create=false", "spring.jpa.open-in-view=false"})
class ReservationIntegrationTest {
    // Loads the real inventory/reservation stack without unrelated payment schedulers or Kafka listeners.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"payment_infra.inventory", "payment_infra.reservation"})
    @EnableJpaRepositories(basePackages = {"payment_infra.inventory", "payment_infra.reservation"})
    @Import({InventoryService.class, ReservationService.class, ReservationController.class})
    static class LabConfiguration {}

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
    @Autowired InventoryService inventory;
    @Autowired ReservationService reservations;
    @Autowired ReservationRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    private final ObjectMapper json = new ObjectMapper();

    // Runs all Flyway migrations against an isolated PostgreSQL database.
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Confirms that different buyers cannot create more reservations than available units.
    @Test
    void hundredBuyersCreateExactlyTenReservations() throws Exception {
        String sku = fresh();
        inventory.create(sku, 10);
        List<HttpResponse<String>> responses = burst(100, i -> fresh(), i -> body(sku, 1));
        assertEquals(10, responses.stream().filter(r -> r.statusCode() == 201).count());
        assertEquals(90, responses.stream().filter(r -> r.statusCode() == 409).count());
        assertEquals(10, count(sku));
        assertEquals(0, inventory.get(sku).getAvailableQuantity());
        assertEquals(10, jdbc.queryForObject("SELECT sum(quantity) FROM reservations WHERE sku = ?", Integer.class, sku));
    }

    // Races a previously unused key and verifies that even retries after sellout reuse one reservation.
    @Test
    void concurrentRetriesDeductOnceAndReturnSameId() throws Exception {
        String sku = fresh();
        String key = fresh();
        inventory.create(sku, 1);
        List<HttpResponse<String>> responses = burst(30, i -> key, i -> body(sku, 1));
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
        assertEquals(29, responses.stream().filter(r -> r.statusCode() == 200).count());
        Set<String> ids = new HashSet<>();
        for (HttpResponse<String> response : responses) ids.add(json.readTree(response.body()).get("id").asText());
        assertEquals(1, ids.size());
        assertEquals(1, count(sku));
        assertEquals(0, inventory.get(sku).getAvailableQuantity());
        var replay = reservations.create(sku, 1, key);
        assertFalse(replay.created());
        assertEquals(ids.iterator().next(), replay.reservation().getId().toString());
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(uri("/reservations/" + replay.reservation().getId()))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("RESERVED", json.readTree(response.body()).get("status").asText());
        }
    }

    // Reusing a key with different request data must not allocate stock for the losing request.
    @Test
    void conflictingConcurrentPayloadsHaveOnlyOneWinner() throws Exception {
        String sku = fresh();
        String key = fresh();
        inventory.create(sku, 10);
        var responses = burst(2, i -> key, i -> body(sku, i + 1));
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 409).count());
        Reservation winner = repository.findByIdempotencyKey(key).orElseThrow();
        assertEquals(10 - winner.getQuantity(), inventory.get(sku).getAvailableQuantity());
        String otherSku = fresh();
        inventory.create(otherSku, 10);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> reservations.create(otherSku, winner.getQuantity(), key)).getStatusCode().value());
        assertEquals(10, inventory.get(otherSku).getAvailableQuantity());
        assertEquals(1, count(sku));
        assertEquals(0, count(otherSku));
    }

    // Insufficient stock rolls back the inserted record and leaves the key available for a later attempt.
    @Test
    void insufficientStockLeavesNoReservationOrPartialDeduction() {
        String sku = fresh();
        String key = fresh();
        inventory.create(sku, 2);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> reservations.create(sku, 3, key)).getStatusCode().value());
        assertEquals(2, inventory.get(sku).getAvailableQuantity());
        assertTrue(repository.findByIdempotencyKey(key).isEmpty());
        assertEquals(0, count(sku));
        assertTrue(reservations.create(sku, 2, key).created());
    }

    // A failure after both writes must roll back stock and reservation, even through nested services.
    @Test
    void failureAfterDeductionRollsBackBothWrites() {
        String sku = fresh();
        String key = fresh();
        inventory.create(sku, 5);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            reservations.create(sku, 2, key);
            assertEquals(3, inventory.get(sku).getAvailableQuantity());
            assertTrue(repository.findByIdempotencyKey(key).isPresent());
            throw new IllegalStateException("Simulated failure before commit");
        }));
        assertEquals(5, inventory.get(sku).getAvailableQuantity());
        assertTrue(repository.findByIdempotencyKey(key).isEmpty());
        assertTrue(reservations.create(sku, 2, key).created());
        assertEquals(3, inventory.get(sku).getAvailableQuantity());
    }

    // Invalid HTTP inputs never reach a successful allocation, and a missing SKU gives 404.
    @Test
    void invalidRequestsDoNotConsumeInventory() throws Exception {
        String sku = fresh();
        inventory.create(sku, 5);
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String payload : List.of(body(sku, 0), body(sku, -1), "{}", body("bad sku", 1))) {
                assertEquals(400, post(client, fresh(), payload).statusCode());
            }
            assertEquals(400, post(client, null, body(sku, 1)).statusCode());
            assertEquals(400, post(client, "", body(sku, 1)).statusCode());
            assertEquals(400, post(client, "x".repeat(256), body(sku, 1)).statusCode());
            assertEquals(404, post(client, fresh(), body(fresh(), 1)).statusCode());
        }
        assertEquals(5, inventory.get(sku).getAvailableQuantity());
        assertEquals(0, count(sku));
    }

    // Starts HTTP calls together so concurrency tests exercise separate service transactions.
    private List<HttpResponse<String>> burst(int number, IntFunction<String> keys,
                                             IntFunction<String> bodies) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient();
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(number);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < number; i++) {
                String key = keys.apply(i);
                String payload = bodies.apply(i);
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("Start gate timed out");
                    return post(client, key, payload);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<HttpResponse<String>> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        }
    }

    // Sends one reservation request through the actual HTTP validation and transaction boundaries.
    private HttpResponse<String> post(HttpClient client, String key, String body) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/reservations")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // Counts committed allocation records for this test's isolated SKU.
    private int count(String sku) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE sku = ?", Integer.class, sku);
    }
    // Builds the small JSON request used by the tests.
    private String body(String sku, int quantity) { return "{\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}"; }
    // Addresses the random test port rather than the development backend.
    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }
    // Keeps records independent across tests.
    private String fresh() { return UUID.randomUUID().toString(); }
}
