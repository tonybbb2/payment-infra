CREATE TABLE inventory (
    sku VARCHAR(100) PRIMARY KEY,
    available_quantity INTEGER NOT NULL,
    CONSTRAINT inventory_quantity_nonnegative CHECK (available_quantity >= 0),
    CONSTRAINT inventory_sku_not_blank CHECK (length(trim(sku)) > 0)
);
