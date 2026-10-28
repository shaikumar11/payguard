package com.payguard.risk;

import java.util.Optional;

public record HighAmountRule(long mediumMinor, long highMinor) implements Rule {
    public HighAmountRule() { this(500_000, 1_000_000); }

    @Override
    public Optional<RuleHit> evaluate(RiskContext ctx) {
        long a = ctx.transfer().amountMinor();
        if (a >= highMinor) return Optional.of(new RuleHit("HIGH_AMOUNT", 50, "amount " + a + " >= " + highMinor));
        if (a >= mediumMinor) return Optional.of(new RuleHit("HIGH_AMOUNT", 25, "amount " + a + " >= " + mediumMinor));
        return Optional.empty();
    }
}
