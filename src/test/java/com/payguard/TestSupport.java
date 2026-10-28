package com.payguard;

import com.payguard.db.Database;
import com.payguard.db.Schema;
import org.junit.jupiter.api.Assumptions;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

final class TestSupport {
    private TestSupport() { }

    /** Clock the test can move forward. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-20T12:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    static Database freshDb(int poolSize) throws SQLException {
        String url = System.getenv("PG_URL");
        Assumptions.assumeTrue(url != null, "PG_URL not set, skipping PostgreSQL integration tests");
        String user = System.getenv().getOrDefault("PG_USER", "payguard");
        String pw = System.getenv().getOrDefault("PG_PASSWORD", "payguard");
        Database db = new Database(url, user, pw, poolSize);
        Schema.create(db);
        db.inTx(c -> {
            try (var s = c.createStatement()) {
                s.execute("TRUNCATE ledger_entries, payments, accounts RESTART IDENTITY CASCADE");
            }
            return null;
        });
        return db;
    }

    static void account(Database db, long id, long balance) throws SQLException {
        db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO accounts(id, owner, currency, balance) VALUES (?,?,'GBP',?)")) {
                ps.setLong(1, id); ps.setString(2, "owner" + id); ps.setLong(3, balance);
                ps.executeUpdate();
            }
            return null;
        });
    }

    static long scalar(Database db, String sql) throws SQLException {
        return db.inTx(c -> {
            try (var s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    static Transfer tx(String key, long from, long to, long amount) {
        return new Transfer(key, from, to, amount, "GBP", "GB", "dev-1");
    }
}
