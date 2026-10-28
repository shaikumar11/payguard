package com.payguard;

import com.payguard.TestSupport.MutableClock;
import com.payguard.db.Database;
import com.payguard.risk.RiskEngine;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.payguard.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class TransferServiceTest {
    private Database db;
    private MutableClock clock;
    private TransferService svc;

    @BeforeEach void setUp() throws Exception {
        db = freshDb(20);
        clock = new MutableClock();
        svc = new TransferService(db, RiskEngine.defaults(), clock);
        account(db, 1, 10_000_000);
        account(db, 2, 0);
    }

    @AfterEach void tearDown() { if (db != null) db.close(); }

    /** Every transfer must keep the books balanced and consistent with account balances. */
    private void assertInvariants(long expectedTotal) throws Exception {
        assertEquals(0, scalar(db, "SELECT COALESCE(SUM(delta),0) FROM ledger_entries"), "ledger must sum to zero");
        assertEquals(expectedTotal, scalar(db, "SELECT SUM(balance) FROM accounts"), "money must be conserved");
        assertEquals(0, scalar(db, "SELECT COUNT(*) FROM accounts WHERE balance < 0"));
    }

    @Test void completedTransferMovesMoneyWithTwoLedgerEntries() throws Exception {
        var r = svc.transfer(tx("k1", 1, 2, 25_000));
        assertEquals(Outcome.COMPLETED, r.outcome());
        assertFalse(r.replayed());
        assertEquals(9_975_000, svc.balance(1));
        assertEquals(25_000, svc.balance(2));
        assertEquals(2, scalar(db, "SELECT COUNT(*) FROM ledger_entries WHERE payment_id = " + r.paymentId()));
        assertInvariants(10_000_000);
    }

    @Test void sameKeySameBodyReplaysWithoutMovingMoneyTwice() throws Exception {
        var first = svc.transfer(tx("k1", 1, 2, 25_000));
        var second = svc.transfer(tx("k1", 1, 2, 25_000));
        assertTrue(second.replayed());
        assertEquals(first.paymentId(), second.paymentId());
        assertEquals(25_000, svc.balance(2));
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM payments"));
    }

    @Test void sameKeyDifferentBodyIsRejected() {
        svc.transfer(tx("k1", 1, 2, 25_000));
        assertThrows(PaymentException.IdempotencyConflict.class, () -> svc.transfer(tx("k1", 1, 2, 26_000)));
    }

    @Test void insufficientFundsLeavesBalancesAlone() throws Exception {
        account(db, 3, 1_000);
        var r = svc.transfer(tx("k1", 3, 2, 2_000));
        assertEquals(Outcome.INSUFFICIENT_FUNDS, r.outcome());
        assertEquals(1_000, svc.balance(3));
        assertEquals(0, scalar(db, "SELECT COUNT(*) FROM ledger_entries"));
    }

    @Test void unknownAccountAndBadInputAreRejected() {
        assertThrows(PaymentException.NotFound.class, () -> svc.transfer(tx("k1", 1, 99, 100)));
        assertThrows(IllegalArgumentException.class, () -> svc.transfer(tx("k2", 1, 1, 100)));
        assertThrows(IllegalArgumentException.class, () -> svc.transfer(tx("k3", 1, 2, 0)));
        assertThrows(IllegalArgumentException.class, () -> svc.transfer(new Transfer("k4", 1, 2, 100, "USD", "GB", "d")));
    }

    @Test void riskyPaymentIsHeldThenApprovedOnce() throws Exception {
        // 600k from a never seen device: high amount 25 + new device 30 = 55 -> review
        var held = svc.transfer(new Transfer("k1", 1, 2, 600_000, "GBP", "GB", "new-device"));
        assertEquals(Outcome.HELD_FOR_REVIEW, held.outcome());
        assertEquals(10_000_000, svc.balance(1));
        assertEquals(List.of(held.paymentId()), svc.heldForReview());

        var done = svc.approve(held.paymentId());
        assertEquals(Outcome.COMPLETED, done.outcome());
        assertEquals(9_400_000, svc.balance(1));
        assertTrue(svc.heldForReview().isEmpty());
        assertThrows(PaymentException.InvalidState.class, () -> svc.approve(held.paymentId()));
        assertInvariants(10_000_000);
    }

    @Test void rejectedReviewMovesNothing() throws Exception {
        var held = svc.transfer(new Transfer("k1", 1, 2, 600_000, "GBP", "GB", "new-device"));
        assertEquals(Outcome.REJECTED_BY_REVIEWER, svc.reject(held.paymentId()).outcome());
        assertEquals(10_000_000, svc.balance(1));
    }

    @Test void veryRiskyPaymentIsDeclined() {
        var r = svc.transfer(new Transfer("k1", 1, 2, 2_000_000, "GBP", "GB", "new-device"));
        assertEquals(Outcome.DECLINED_RISK, r.outcome());
        assertTrue(r.reasons().stream().anyMatch(s -> s.startsWith("HIGH_AMOUNT")));
    }

    @Test void velocityTriggersOnSixthPaymentInAMinute() {
        for (int i = 0; i < 5; i++) {
            assertEquals(Outcome.COMPLETED, svc.transfer(tx("v" + i, 1, 2, 100)).outcome());
            clock.advance(Duration.ofSeconds(2));
        }
        var sixth = svc.transfer(tx("v5", 1, 2, 100));
        assertTrue(sixth.reasons().stream().anyMatch(s -> s.startsWith("VELOCITY")));
        assertEquals(40, sixth.riskScore());
        assertEquals(Outcome.HELD_FOR_REVIEW, sixth.outcome());
        clock.advance(Duration.ofMinutes(2));
        assertEquals(Outcome.COMPLETED, svc.transfer(tx("v6", 1, 2, 100)).outcome());
    }

    @Test void countryHopIsFlagged() {
        svc.transfer(new Transfer("a", 1, 2, 100, "GBP", "GB", "dev-1"));
        clock.advance(Duration.ofMinutes(2));
        var r = svc.transfer(new Transfer("b", 1, 2, 100, "GBP", "FR", "dev-1"));
        assertTrue(r.reasons().stream().anyMatch(s -> s.startsWith("COUNTRY_HOP")));
        assertEquals(Outcome.HELD_FOR_REVIEW, r.outcome());
    }

    @Test void manyConcurrentTransfersNeverOverdrawAndConserveMoney() throws Exception {
        account(db, 3, 5_000);                       // can afford exactly 50 payments of 100
        int attempts = 120;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TransferResult>> fs = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String key = "c" + i;
                fs.add(pool.submit(() -> svc.transfer(tx(key, 3, 2, 100))));
            }
            int done = 0, noFunds = 0, held = 0;
            for (var f : fs) {
                switch (f.get(60, TimeUnit.SECONDS).outcome()) {
                    case COMPLETED -> done++;
                    case INSUFFICIENT_FUNDS -> noFunds++;
                    case HELD_FOR_REVIEW, DECLINED_RISK -> held++;
                    default -> fail("unexpected outcome");
                }
            }
            assertEquals(attempts, done + noFunds + held);
            assertTrue(done <= 50, "cannot complete more than the balance allows");
            assertEquals(5_000 - done * 100L, svc.balance(3));
        }
        assertInvariants(10_005_000);
    }

    @Test void concurrentDuplicateKeysProduceExactlyOnePayment() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TransferResult>> fs = new ArrayList<>();
            for (int i = 0; i < 25; i++) fs.add(pool.submit(() -> svc.transfer(tx("same-key", 1, 2, 7_000))));
            long ids = fs.stream().map(f -> { try { return f.get(60, TimeUnit.SECONDS).paymentId(); } catch (Exception e) { throw new RuntimeException(e); } })
                    .distinct().count();
            assertEquals(1, ids);
            long replays = fs.stream().filter(f -> { try { return f.get().replayed(); } catch (Exception e) { return false; } }).count();
            assertEquals(24, replays);
        }
        assertEquals(7_000, svc.balance(2));
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM payments"));
        assertInvariants(10_000_000);
    }

    @Test void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        account(db, 3, 2_000_000);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<TransferResult>> fs = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String key = "d" + i;
                boolean forward = i % 2 == 0;
                fs.add(pool.submit(() -> forward ? svc.transfer(tx(key, 3, 1, 10)) : svc.transfer(tx(key, 1, 3, 10))));
            }
            for (var f : fs) assertNotNull(f.get(60, TimeUnit.SECONDS));
        }
        assertInvariants(12_000_000);
    }
}
