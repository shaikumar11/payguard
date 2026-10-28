package com.payguard;

import com.payguard.db.Database;
import com.payguard.risk.RiskContext;
import com.payguard.risk.RiskContext.PriorPayment;
import com.payguard.risk.RiskEngine;
import com.payguard.risk.RiskResult;
import com.payguard.risk.RuleHit;

import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Idempotent, risk-scored money movement with a double entry ledger.
 *
 * Concurrency design:
 *  - both accounts are locked with SELECT ... FOR UPDATE in ascending id order before anything else is
 *    written, so opposite direction transfers cannot deadlock;
 *  - the payment row is then inserted under a UNIQUE idempotency key, so a concurrent duplicate waits for the
 *    account lock, hits the conflict and replays the stored result;
 *  - a CHECK (balance >= 0) constraint is the last line of defence against overdraft.
 */
public final class TransferService {
    private static final Duration LOOKBACK = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 3;

    private final Database db;
    private final RiskEngine risk;
    private final Clock clock;

    public TransferService(Database db, RiskEngine risk, Clock clock) {
        this.db = db;
        this.risk = risk;
        this.clock = clock;
    }

    public TransferResult transfer(Transfer t) {
        t.validate();
        for (int attempt = 1; ; attempt++) {
            try {
                return db.inTx(c -> doTransfer(c, t));
            } catch (SQLException e) {
                boolean retryable = "40P01".equals(e.getSQLState()) || "40001".equals(e.getSQLState());
                if (retryable && attempt < MAX_ATTEMPTS) continue;
                throw new PaymentException("database error: " + e.getMessage(), e);
            }
        }
    }

    private TransferResult doTransfer(Connection c, Transfer t) throws SQLException {
        Instant now = clock.instant();

        // Lock both accounts first, in ascending id order. This must come before the payments INSERT:
        // that INSERT takes foreign key locks on both accounts in request order, which would reintroduce deadlocks.
        Map<Long, long[]> locked = lockAccounts(c, t.fromAccount(), t.toAccount());
        for (long id : new long[]{t.fromAccount(), t.toAccount()})
            if (!locked.containsKey(id)) throw new PaymentException.NotFound("account " + id);
        String fromCurrency = currencyOf(c, t.fromAccount());
        if (!fromCurrency.equals(t.currency()) || !fromCurrency.equals(currencyOf(c, t.toAccount())))
            throw new IllegalArgumentException("currency mismatch between request and accounts");

        long paymentId;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO payments(idempotency_key, request_hash, from_account, to_account, amount, currency, country, "
                        + "device_id, outcome, created_at) VALUES (?,?,?,?,?,?,?,?,'PROCESSING',?) "
                        + "ON CONFLICT (idempotency_key) DO NOTHING RETURNING id")) {
            ps.setString(1, t.idempotencyKey());
            ps.setString(2, t.fingerprint());
            ps.setLong(3, t.fromAccount());
            ps.setLong(4, t.toAccount());
            ps.setLong(5, t.amountMinor());
            ps.setString(6, t.currency());
            ps.setString(7, t.country());
            ps.setString(8, t.deviceId());
            ps.setTimestamp(9, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return replay(c, t);
                paymentId = rs.getLong(1);
            }
        }

        RiskResult rr = risk.evaluate(new RiskContext(t, now, priorPayments(c, t.fromAccount(), paymentId, now),
                knownDevices(c, t.fromAccount())));
        List<String> reasons = rr.hits().stream().map(RuleHit::describe).toList();

        Outcome outcome;
        switch (rr.decision()) {
            case DECLINE -> outcome = Outcome.DECLINED_RISK;
            case REVIEW -> outcome = Outcome.HELD_FOR_REVIEW;
            default -> {
                if (locked.get(t.fromAccount())[0] < t.amountMinor()) outcome = Outcome.INSUFFICIENT_FUNDS;
                else {
                    move(c, paymentId, t.fromAccount(), t.toAccount(), t.amountMinor(), now);
                    outcome = Outcome.COMPLETED;
                }
            }
        }
        setOutcome(c, paymentId, outcome, rr.score(), reasons);
        return new TransferResult(paymentId, outcome, rr.score(), reasons, false);
    }

    private TransferResult replay(Connection c, Transfer t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, request_hash, outcome, risk_score, reasons FROM payments WHERE idempotency_key = ?")) {
            ps.setString(1, t.idempotencyKey());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("conflicting row vanished", "40001");
                if (!rs.getString(2).equals(t.fingerprint())) throw new PaymentException.IdempotencyConflict(t.idempotencyKey());
                return new TransferResult(rs.getLong(1), Outcome.valueOf(rs.getString(3)), rs.getInt(4),
                        splitReasons(rs.getString(5)), true);
            }
        }
    }

    /** Releases a held payment: moves the money if the account can still cover it. */
    public TransferResult approve(long paymentId) {
        return review(paymentId, true);
    }

    public TransferResult reject(long paymentId) {
        return review(paymentId, false);
    }

    private TransferResult review(long paymentId, boolean approve) {
        try {
            return db.inTx(c -> {
                long from, to, amount, score;
                String reasons;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT from_account, to_account, amount, outcome, risk_score, reasons FROM payments WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, paymentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new PaymentException.NotFound("payment " + paymentId);
                        if (!Outcome.HELD_FOR_REVIEW.name().equals(rs.getString(4)))
                            throw new PaymentException.InvalidState("payment " + paymentId + " is " + rs.getString(4) + ", not held for review");
                        from = rs.getLong(1); to = rs.getLong(2); amount = rs.getLong(3);
                        score = rs.getInt(5); reasons = rs.getString(6);
                    }
                }
                Outcome outcome;
                if (!approve) outcome = Outcome.REJECTED_BY_REVIEWER;
                else {
                    Map<Long, long[]> locked = lockAccounts(c, from, to);
                    if (locked.get(from)[0] < amount) outcome = Outcome.INSUFFICIENT_FUNDS;
                    else {
                        move(c, paymentId, from, to, amount, clock.instant());
                        outcome = Outcome.COMPLETED;
                    }
                }
                setOutcome(c, paymentId, outcome, (int) score, splitReasons(reasons));
                return new TransferResult(paymentId, outcome, (int) score, splitReasons(reasons), false);
            });
        } catch (SQLException e) {
            throw new PaymentException("database error: " + e.getMessage(), e);
        }
    }

    public List<Long> heldForReview() {
        try {
            return db.inTx(c -> {
                List<Long> ids = new ArrayList<>();
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT id FROM payments WHERE outcome = 'HELD_FOR_REVIEW' ORDER BY id")) {
                    while (rs.next()) ids.add(rs.getLong(1));
                }
                return ids;
            });
        } catch (SQLException e) {
            throw new PaymentException("database error: " + e.getMessage(), e);
        }
    }

    public TransferResult find(long paymentId) {
        try {
            return db.inTx(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT outcome, risk_score, reasons FROM payments WHERE id = ?")) {
                    ps.setLong(1, paymentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new PaymentException.NotFound("payment " + paymentId);
                        return new TransferResult(paymentId, Outcome.valueOf(rs.getString(1)), rs.getInt(2),
                                splitReasons(rs.getString(3)), false);
                    }
                }
            });
        } catch (SQLException e) {
            throw new PaymentException("database error: " + e.getMessage(), e);
        }
    }

    public long balance(long accountId) {
        try {
            return db.inTx(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE id = ?")) {
                    ps.setLong(1, accountId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new PaymentException.NotFound("account " + accountId);
                        return rs.getLong(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new PaymentException("database error: " + e.getMessage(), e);
        }
    }

    // ---- helpers ----

    private static Map<Long, long[]> lockAccounts(Connection c, long a, long b) throws SQLException {
        Map<Long, long[]> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, balance FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE")) {
            ps.setLong(1, a);
            ps.setLong(2, b);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getLong(1), new long[]{rs.getLong(2)});
            }
        }
        return out;
    }

    private static String currencyOf(Connection c, long account) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT currency FROM accounts WHERE id = ?")) {
            ps.setLong(1, account);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1).trim();
            }
        }
    }

    private static void move(Connection c, long paymentId, long from, long to, long amount, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET balance = balance + ? WHERE id = ?")) {
            ps.setLong(1, -amount); ps.setLong(2, from); ps.executeUpdate();
            ps.setLong(1, amount); ps.setLong(2, to); ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ledger_entries(payment_id, account_id, delta, created_at) VALUES (?,?,?,?)")) {
            ps.setLong(1, paymentId); ps.setLong(2, from); ps.setLong(3, -amount); ps.setTimestamp(4, Timestamp.from(now));
            ps.executeUpdate();
            ps.setLong(1, paymentId); ps.setLong(2, to); ps.setLong(3, amount); ps.setTimestamp(4, Timestamp.from(now));
            ps.executeUpdate();
        }
    }

    private static void setOutcome(Connection c, long paymentId, Outcome o, int score, List<String> reasons) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE payments SET outcome = ?, risk_score = ?, reasons = ? WHERE id = ?")) {
            ps.setString(1, o.name());
            ps.setInt(2, score);
            ps.setString(3, String.join(" ; ", reasons));
            ps.setLong(4, paymentId);
            ps.executeUpdate();
        }
    }

    private static List<PriorPayment> priorPayments(Connection c, long from, long self, Instant now) throws SQLException {
        List<PriorPayment> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT created_at, country FROM payments WHERE from_account = ? AND id <> ? AND created_at >= ? "
                        + "AND outcome <> 'PROCESSING'")) {
            ps.setLong(1, from);
            ps.setLong(2, self);
            ps.setTimestamp(3, Timestamp.from(now.minus(LOOKBACK)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new PriorPayment(rs.getTimestamp(1).toInstant(), rs.getString(2).trim()));
            }
        }
        return out;
    }

    private static Set<String> knownDevices(Connection c, long from) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT device_id FROM payments WHERE from_account = ? AND outcome = 'COMPLETED'")) {
            ps.setLong(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static List<String> splitReasons(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.split(" ; "));
    }
}
