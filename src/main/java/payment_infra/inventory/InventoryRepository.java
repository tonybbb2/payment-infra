package payment_infra.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, String> {
    // Creates a SKU once; an existing SKU is never reset by another creation request.
    @Modifying
    @Query(value = """
            INSERT INTO inventory (sku, available_quantity)
            VALUES (:sku, :quantity)
            ON CONFLICT (sku) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("sku") String sku, @Param("quantity") int quantity);

    // Checks and deducts stock in one statement; competing updates wait and recheck availability.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE inventory
            SET available_quantity = available_quantity - :quantity
            WHERE sku = :sku
              AND :quantity > 0
              AND available_quantity >= :quantity
            """, nativeQuery = true)
    int tryDecrement(@Param("sku") String sku, @Param("quantity") int quantity);
}
