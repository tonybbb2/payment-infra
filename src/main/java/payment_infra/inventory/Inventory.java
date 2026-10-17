package payment_infra.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory")
public class Inventory {
    @Id
    @Column(length = 100)
    private String sku;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    // JPA uses this constructor when loading a row from PostgreSQL.
    protected Inventory() {
    }

    // Returns the unique product code identifying this stock row.
    public String getSku() {
        return sku;
    }

    // Returns the available quantity observed when this row was loaded.
    public int getAvailableQuantity() {
        return availableQuantity;
    }
}
