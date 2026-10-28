package com.payguard.db;

import java.sql.SQLException;
import java.sql.Statement;

public final class Schema {
    private Schema() { }

    private static final String DDL = """
        CREATE TABLE IF NOT EXISTS accounts (
            id        BIGINT PRIMARY KEY,
            owner     TEXT NOT NULL,
            currency  CHAR(3) NOT NULL,
            balance   BIGINT NOT NULL CHECK (balance >= 0)
        );
        CREATE TABLE IF NOT EXISTS payments (
            id               BIGSERIAL PRIMARY KEY,
            idempotency_key  TEXT NOT NULL UNIQUE,
            request_hash     TEXT NOT NULL,
            from_account     BIGINT NOT NULL REFERENCES accounts(id),
            to_account       BIGINT NOT NULL REFERENCES accounts(id),
            amount           BIGINT NOT NULL CHECK (amount > 0),
            currency         CHAR(3) NOT NULL,
            country          CHAR(2) NOT NULL,
            device_id        TEXT NOT NULL,
            outcome          TEXT NOT NULL,
            risk_score       INT NOT NULL DEFAULT 0,
            reasons          TEXT NOT NULL DEFAULT '',
            created_at       TIMESTAMPTZ NOT NULL
        );
        CREATE INDEX IF NOT EXISTS ix_payments_from_time ON payments (from_account, created_at);
        CREATE INDEX IF NOT EXISTS ix_payments_outcome ON payments (outcome) WHERE outcome = 'HELD_FOR_REVIEW';
        CREATE TABLE IF NOT EXISTS ledger_entries (
            id          BIGSERIAL PRIMARY KEY,
            payment_id  BIGINT NOT NULL REFERENCES payments(id),
            account_id  BIGINT NOT NULL REFERENCES accounts(id),
            delta       BIGINT NOT NULL,
            created_at  TIMESTAMPTZ NOT NULL
        );
        CREATE INDEX IF NOT EXISTS ix_ledger_account ON ledger_entries (account_id);
        """;

    public static void create(Database db) throws SQLException {
        db.inTx(c -> {
            try (Statement s = c.createStatement()) { s.execute(DDL); }
            return null;
        });
    }
}
