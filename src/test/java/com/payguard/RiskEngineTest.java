package com.payguard;

import com.payguard.risk.*;
import com.payguard.risk.RiskContext.PriorPayment;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RiskEngineTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    private static Transfer t(long amount, String country, String device) {
        return new Transfer("k", 1, 2, amount, "GBP", country, device);
    }

    private static RiskContext ctx(Transfer t, List<PriorPayment> prior, Set<String> devices) {
        return new RiskContext(t, NOW, prior, devices);
    }

    @Test void smallKnownPaymentIsApproved() {
        var r = RiskEngine.defaults().evaluate(ctx(t(1_000, "GB", "d1"), List.of(), Set.of("d1")));
        assertEquals(RiskResult.Decision.APPROVE, r.decision());
        assertEquals(0, r.score());
    }

    @Test void highAmountTiers() {
        var rule = new HighAmountRule();
        assertTrue(rule.evaluate(ctx(t(499_999, "GB", "d"), List.of(), Set.of())).isEmpty());
        assertEquals(25, rule.evaluate(ctx(t(500_000, "GB", "d"), List.of(), Set.of())).get().points());
        assertEquals(50, rule.evaluate(ctx(t(1_000_000, "GB", "d"), List.of(), Set.of())).get().points());
    }

    @Test void velocityNeedsFiveAttemptsInsideWindow() {
        var rule = new VelocityRule(Duration.ofSeconds(60), 5);
        var four = List.of(new PriorPayment(NOW.minusSeconds(5), "GB"), new PriorPayment(NOW.minusSeconds(6), "GB"),
                new PriorPayment(NOW.minusSeconds(7), "GB"), new PriorPayment(NOW.minusSeconds(8), "GB"));
        assertTrue(rule.evaluate(ctx(t(100, "GB", "d"), four, Set.of())).isEmpty());
        var five = new java.util.ArrayList<>(four);
        five.add(new PriorPayment(NOW.minusSeconds(9), "GB"));
        assertTrue(rule.evaluate(ctx(t(100, "GB", "d"), five, Set.of())).isPresent());
        var stale = List.of(new PriorPayment(NOW.minusSeconds(120), "GB"), new PriorPayment(NOW.minusSeconds(121), "GB"),
                new PriorPayment(NOW.minusSeconds(122), "GB"), new PriorPayment(NOW.minusSeconds(123), "GB"),
                new PriorPayment(NOW.minusSeconds(124), "GB"));
        assertTrue(rule.evaluate(ctx(t(100, "GB", "d"), stale, Set.of())).isEmpty());
    }

    @Test void countryHopUsesMostRecentPaymentOnly() {
        var rule = new CountryHopRule();
        var hop = List.of(new PriorPayment(NOW.minusSeconds(120), "GB"));
        assertTrue(rule.evaluate(ctx(t(100, "FR", "d"), hop, Set.of())).isPresent());
        assertTrue(rule.evaluate(ctx(t(100, "GB", "d"), hop, Set.of())).isEmpty());
        var mixed = List.of(new PriorPayment(NOW.minusSeconds(300), "FR"), new PriorPayment(NOW.minusSeconds(60), "GB"));
        assertTrue(rule.evaluate(ctx(t(100, "GB", "d"), mixed, Set.of())).isEmpty());
        var old = List.of(new PriorPayment(NOW.minus(Duration.ofMinutes(30)), "GB"));
        assertTrue(rule.evaluate(ctx(t(100, "FR", "d"), old, Set.of())).isEmpty());
    }

    @Test void newDeviceOnlyMattersForLargerAmounts() {
        var rule = new NewDeviceRule();
        assertTrue(rule.evaluate(ctx(t(99_999, "GB", "new"), List.of(), Set.of())).isEmpty());
        assertTrue(rule.evaluate(ctx(t(100_000, "GB", "new"), List.of(), Set.of())).isPresent());
        assertTrue(rule.evaluate(ctx(t(100_000, "GB", "old"), List.of(), Set.of("old"))).isEmpty());
    }

    @Test void scoresCombineAndThresholdsApply() {
        // high amount 50 + new device 30 = 80 -> decline
        var declined = RiskEngine.defaults().evaluate(ctx(t(1_000_000, "GB", "new"), List.of(), Set.of()));
        assertEquals(80, declined.score());
        assertEquals(RiskResult.Decision.DECLINE, declined.decision());
        // high amount 25 + new device 30 = 55 -> review
        var review = RiskEngine.defaults().evaluate(ctx(t(500_000, "GB", "new"), List.of(), Set.of()));
        assertEquals(RiskResult.Decision.REVIEW, review.decision());
    }

    @Test void scoreIsCappedAt100() {
        var prior = List.of(new PriorPayment(NOW.minusSeconds(1), "FR"), new PriorPayment(NOW.minusSeconds(2), "FR"),
                new PriorPayment(NOW.minusSeconds(3), "FR"), new PriorPayment(NOW.minusSeconds(4), "FR"),
                new PriorPayment(NOW.minusSeconds(5), "FR"));
        var r = RiskEngine.defaults().evaluate(ctx(t(2_000_000, "GB", "new"), prior, Set.of()));
        assertEquals(100, r.score());
    }
}
