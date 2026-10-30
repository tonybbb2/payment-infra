CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    sku VARCHAR(100) NOT NULL REFERENCES inventory(sku),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED')),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT reservation_key_not_blank CHECK (length(trim(idempotency_key)) > 0)
);

CREATE INDEX idx_reservations_sku ON reservations(sku);
