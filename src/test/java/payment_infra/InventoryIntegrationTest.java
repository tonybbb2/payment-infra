package payment_infra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false"})
class InventoryIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @LocalServerPort int port;

    // Points Flyway and JPA at a real, isolated PostgreSQL instance.
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Sends 100 overlapping HTTP attempts and checks both their outcomes and the committed stock.
    @Test
    void tenUnitsAllowExactlyTenOfOneHundredAttempts() throws Exception {
        String sku = sku();
        inventory.create(sku, 10);
        try (HttpClient client = HttpClient.newHttpClient();
             ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch ready = new CountDownLatch(100);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException("Start gate timed out");
                    return post(client, "/inventory/" + sku + "/decrement", "{\"quantity\":1}");
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            int conflicts = 0;
            for (Future<Integer> result : results) {
                int status = result.get(30, TimeUnit.SECONDS);
                if (status == 204) successes++;
                else if (status == 409) conflicts++;
                else fail("Unexpected HTTP status: " + status);
            }
            assertEquals(10, successes);
            assertEquals(90, conflicts);
            assertEquals(0, inventory.get(sku).getAvailableQuantity());
        }
    }

    // Demonstrates that a waiting buyer can take the last unit when the first transaction rolls back.
    @Test
    void rollbackLetsWaitingBuyerAcquireLastUnit() throws Exception {
        String sku = sku();
        inventory.create(sku, 1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> waitingBuyer = transaction.execute(status -> {
                inventory.decrement(sku, 1);
                CountDownLatch started = new CountDownLatch(1);
                Future<?> second = workers.submit(() -> {
                    started.countDown();
                    inventory.decrement(sku, 1);
                });
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    status.setRollbackOnly();
                }
                return second;
            });
            assertNotNull(waitingBuyer);
            waitingBuyer.get(10, TimeUnit.SECONDS);
            assertEquals(0, inventory.get(sku).getAvailableQuantity());
        }
    }

    // A failed multi-unit request must leave all existing units available.
    @Test
    void insufficientQuantityDoesNotPartiallyDeduct() {
        String sku = sku();
        inventory.create(sku, 3);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> inventory.decrement(sku, 4));
        assertEquals(409, error.getStatusCode().value());
        assertEquals(3, inventory.get(sku).getAvailableQuantity());
        inventory.decrement(sku, 2);
        assertEquals(1, inventory.get(sku).getAvailableQuantity());
    }

    // Invalid HTTP quantities and missing products must not change stock.
    @Test
    void validatesRequestsAndDistinguishesMissingSku() throws Exception {
        String sku = sku();
        inventory.create(sku, 2);
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String body : List.of("{}", "{\"quantity\":0}", "{\"quantity\":-1}")) {
                assertEquals(400, post(client, "/inventory/" + sku + "/decrement", body));
            }
            assertEquals(404, post(client, "/inventory/" + sku() + "/decrement", "{\"quantity\":1}"));
            assertEquals(400, post(client, "/inventory", "{\"sku\":\"bad sku\",\"quantity\":1}"));
            assertEquals(400, post(client, "/inventory", "{\"sku\":\"valid\",\"quantity\":-1}"));
        }
        assertEquals(2, inventory.get(sku).getAvailableQuantity());
    }

    // A duplicate setup request must not replenish stock that another request already consumed.
    @Test
    void duplicateCreationDoesNotResetStock() throws Exception {
        String sku = sku();
        String body = "{\"sku\":\"" + sku + "\",\"quantity\":2}";
        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(201, post(client, "/inventory", body));
            inventory.decrement(sku, 1);
            assertEquals(409, post(client, "/inventory", body));
        }
        assertEquals(1, inventory.get(sku).getAvailableQuantity());
    }

    // Even SQL that bypasses the service cannot commit a negative quantity.
    @Test
    void databaseRejectsNegativeStock() {
        String sku = sku();
        inventory.create(sku, 1);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE inventory SET available_quantity = -1 WHERE sku = ?", sku));
        assertEquals(1, inventory.get(sku).getAvailableQuantity());
    }

    // Service validation also protects callers that do not enter through HTTP.
    @Test
    void serviceRejectsNonpositiveDeductions() {
        String sku = sku();
        inventory.create(sku, 1);
        for (int quantity : new int[]{0, -1}) {
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> inventory.decrement(sku, quantity));
            assertEquals(400, error.getStatusCode().value());
        }
        assertEquals(1, inventory.get(sku).getAvailableQuantity());
    }

    // Uses a fresh SKU so tests do not share stock or depend on execution order.
    private String sku() {
        return "test-" + UUID.randomUUID();
    }

    // Sends JSON through the real HTTP endpoint with a bounded request timeout.
    private int post(HttpClient client, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
