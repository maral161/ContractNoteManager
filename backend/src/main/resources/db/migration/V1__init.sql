-- ContractNoteManager schema (see docs/ARCHITECTURE_PLAN.md, section 4.6)

CREATE TABLE custody (
    id                     BIGSERIAL PRIMARY KEY,
    sf_key                 VARCHAR(64)  NOT NULL UNIQUE,
    name                   VARCHAR(200) NOT NULL,
    tag                    VARCHAR(100),
    domicile               VARCHAR(2),
    contract_notes_enabled BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted                BOOLEAN      NOT NULL DEFAULT FALSE,
    raw_payload            JSONB,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE asset (
    id                       BIGSERIAL PRIMARY KEY,
    sf_key                   VARCHAR(64)  NOT NULL UNIQUE,
    name                     VARCHAR(300) NOT NULL,
    type                     VARCHAR(30)  NOT NULL,
    isin                     VARCHAR(12),
    domicile                 VARCHAR(2),
    quote_currency_code      VARCHAR(3),
    settlement_currency_code VARCHAR(3),
    settlement_duration      INTEGER,
    qty_decimals             INTEGER,
    classifications          JSONB,
    type_data                JSONB,
    deleted                  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_asset_isin ON asset(isin);

CREATE TABLE owner (
    id          BIGSERIAL PRIMARY KEY,
    sf_key      VARCHAR(64)  NOT NULL UNIQUE,
    name        VARCHAR(200),
    email       VARCHAR(320),
    type        VARCHAR(50),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE broker (
    id          BIGSERIAL PRIMARY KEY,
    sf_key      VARCHAR(64)  UNIQUE,              -- NULL for brokers typed in locally
    name        VARCHAR(200) NOT NULL,
    bic         VARCHAR(11),
    lei         VARCHAR(20),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE portfolio (
    id          BIGSERIAL PRIMARY KEY,
    sf_key      VARCHAR(64)  NOT NULL UNIQUE,     -- allocation[].key
    name        VARCHAR(300) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id                        BIGSERIAL PRIMARY KEY,
    sf_key                    VARCHAR(64)   NOT NULL UNIQUE,
    sf_version                INTEGER       NOT NULL,
    custody_id                BIGINT        NOT NULL REFERENCES custody(id),
    asset_id                  BIGINT        NOT NULL REFERENCES asset(id),
    owner_id                  BIGINT        REFERENCES owner(id),
    broker_id                 BIGINT        REFERENCES broker(id),
    status                    VARCHAR(20)   NOT NULL,
    sf_status                 VARCHAR(30),
    order_type                VARCHAR(20)   NOT NULL,     -- quantity | amount
    side                      VARCHAR(10)   NOT NULL,     -- buy | sell
    price                     NUMERIC(24,8),
    value                     NUMERIC(24,8) NOT NULL,
    currency_code             VARCHAR(3)    NOT NULL,
    settlement_amount         NUMERIC(24,8),
    commission                NUMERIC(24,8) NOT NULL DEFAULT 0,
    accrued_interest          NUMERIC(24,8) NOT NULL DEFAULT 0,
    up_front_fee              NUMERIC(24,8) NOT NULL DEFAULT 0,
    issuer_fee                NUMERIC(24,8) NOT NULL DEFAULT 0,
    booked_date               DATE          NOT NULL,
    traded_date               DATE,
    settlement_date           DATE,
    valid_to                  DATE,
    source                    VARCHAR(30),
    comment                   TEXT,
    partial_fill_allowed      BOOLEAN       NOT NULL DEFAULT FALSE,
    is_merged                 BOOLEAN       NOT NULL DEFAULT FALSE,
    handled_manually          BOOLEAN       NOT NULL DEFAULT FALSE,
    handled_manually_eligible BOOLEAN       NOT NULL DEFAULT FALSE,
    exchange                  JSONB,
    sf_deleted                BOOLEAN       NOT NULL DEFAULT FALSE,
    locally_modified          BOOLEAN       NOT NULL DEFAULT FALSE,
    sync_conflict             BOOLEAN       NOT NULL DEFAULT FALSE,
    details_missing           BOOLEAN       NOT NULL DEFAULT FALSE,
    last_synced_at            TIMESTAMPTZ,
    details_imported_at       TIMESTAMPTZ,
    raw_payload               JSONB,
    version                   INTEGER       NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_orders_booked_date     ON orders(booked_date);
CREATE INDEX ix_orders_traded_date     ON orders(traded_date);
CREATE INDEX ix_orders_settlement_date ON orders(settlement_date);
CREATE INDEX ix_orders_status          ON orders(status);
CREATE INDEX ix_orders_asset           ON orders(asset_id);

CREATE TABLE order_allocation (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT        NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    portfolio_id        BIGINT        NOT NULL REFERENCES portfolio(id),
    value               NUMERIC(24,8) NOT NULL,
    original_value      NUMERIC(24,8),
    status              VARCHAR(30),
    reason              TEXT,
    external_id         VARCHAR(100),
    commission          NUMERIC(24,8),
    portfolio_quantity  NUMERIC(24,8),
    portfolio_weight    NUMERIC(9,4),
    target_quantity     NUMERIC(24,8),
    target_weight       NUMERIC(9,4),
    order_weight        NUMERIC(9,4),
    UNIQUE (order_id, portfolio_id)
);

CREATE TABLE order_status_history (
    id           BIGSERIAL PRIMARY KEY,
    order_id     BIGINT      NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    from_status  VARCHAR(20),
    to_status    VARCHAR(20) NOT NULL,
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    trigger      VARCHAR(20) NOT NULL,          -- USER | CONTRACT_NOTE | IMPORT
    note         TEXT
);
CREATE INDEX ix_status_history_order ON order_status_history(order_id);

CREATE TABLE contract_note (
    id                 BIGSERIAL PRIMARY KEY,
    file_name          VARCHAR(300)  NOT NULL,
    file_sha256        VARCHAR(64)   NOT NULL UNIQUE,
    file_content       BYTEA         NOT NULL,
    instrument_name    VARCHAR(300),
    isin               VARCHAR(12),
    currency_code      VARCHAR(3),
    quantity           NUMERIC(24,8),
    price              NUMERIC(24,8),
    settlement_amount  NUMERIC(24,8),
    broker             VARCHAR(200),
    commission         NUMERIC(24,8),
    side               VARCHAR(10),
    trade_date         DATE,
    warnings           TEXT,
    extraction_json    JSONB,
    extraction_model   VARCHAR(50),
    status             VARCHAR(20)   NOT NULL,  -- MATCHED | UNMATCHED | EXTRACTION_FAILED
    unmatched_reason   TEXT,
    order_id           BIGINT UNIQUE REFERENCES orders(id) ON DELETE SET NULL,
    matched_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_contract_note_status ON contract_note(status);

CREATE TABLE import_run (
    id             BIGSERIAL PRIMARY KEY,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL,        -- RUNNING | SUCCESS | PARTIAL | FAILED
    from_date      DATE,
    to_date        DATE,
    expected_count INTEGER,
    created_count  INTEGER     NOT NULL DEFAULT 0,
    updated_count  INTEGER     NOT NULL DEFAULT 0,
    skipped_count  INTEGER     NOT NULL DEFAULT 0,
    conflict_count INTEGER     NOT NULL DEFAULT 0,
    failed_count   INTEGER     NOT NULL DEFAULT 0,
    details_missing_count INTEGER NOT NULL DEFAULT 0,
    error_message  TEXT
);
