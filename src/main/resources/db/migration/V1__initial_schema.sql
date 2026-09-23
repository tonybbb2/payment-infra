CREATE TABLE payments (
    id UUID PRIMARY KEY,

    amount NUMERIC(19, 2) NOT NULL,

    currency VARCHAR(3) NOT NULL,

    status VARCHAR(50) NOT NULL,

    idempotency_key VARCHAR(255) NOT NULL,

    processor_transaction_id VARCHAR(255),

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_payment_idempotency_key
        UNIQUE (idempotency_key),

    CONSTRAINT payments_status_check
        CHECK (
            status IN (
                'CREATED',
                'AUTHORIZED',
                'CAPTURED',
                'REFUNDED',
                'FAILED',
                'UNKNOWN'
            )
        )
);


CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,

    payment_id UUID NOT NULL,

    transaction_type VARCHAR(50) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_ledger_payment_transaction_type
        UNIQUE (
            payment_id,
            transaction_type
        ),

    CONSTRAINT ledger_transaction_type_check
        CHECK (
            transaction_type IN (
                'CAPTURE',
                'REFUND'
            )
        )
);


CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,

    ledger_transaction_id UUID NOT NULL,

    account VARCHAR(50) NOT NULL,

    entry_type VARCHAR(50) NOT NULL,

    amount NUMERIC(19, 2) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ledger_entry_amount_positive
        CHECK (amount > 0),

    CONSTRAINT ledger_account_check
        CHECK (
            account IN (
                'PROCESSOR_CLEARING',
                'MERCHANT_PAYABLE'
            )
        ),

    CONSTRAINT ledger_entry_type_check
        CHECK (
            entry_type IN (
                'DEBIT',
                'CREDIT'
            )
        ),

    CONSTRAINT fk_ledger_entry_transaction
        FOREIGN KEY (ledger_transaction_id)
        REFERENCES ledger_transactions(id)
);


CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,

    aggregate_type VARCHAR(255) NOT NULL,

    aggregate_id UUID NOT NULL,

    event_type VARCHAR(50) NOT NULL,

    payload TEXT NOT NULL,

    status VARCHAR(50) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,

    published_at TIMESTAMPTZ,

    CONSTRAINT outbox_event_type_check
        CHECK (
            event_type IN (
                'PAYMENT_AUTHORIZED',
                'PAYMENT_CAPTURED',
                'PAYMENT_REFUNDED',
                'PAYMENT_FAILED',
                'PAYMENT_RECONCILED'
            )
        ),

    CONSTRAINT outbox_status_check
        CHECK (
            status IN (
                'PENDING',
                'PUBLISHED'
            )
        )
);


CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,

    processed_at TIMESTAMPTZ NOT NULL
);


CREATE INDEX idx_payments_status
    ON payments(status);


CREATE INDEX idx_ledger_transactions_payment_id
    ON ledger_transactions(payment_id);


CREATE INDEX idx_ledger_entries_transaction_id
    ON ledger_entries(ledger_transaction_id);


CREATE INDEX idx_outbox_status_created_at
    ON outbox_events(status, created_at);


CREATE INDEX idx_outbox_aggregate_id
    ON outbox_events(aggregate_id);