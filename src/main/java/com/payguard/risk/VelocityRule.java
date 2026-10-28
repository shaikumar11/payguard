package com.payguard.risk;

import java.time.Duration;
import java.util.Optional;

/** Too many attempts from one account in a short window, whatever their outcome. */
public record VelocityRule(Duration window, int maxAttempts) implements Rule {
    public VelocityRule() { this(Duration.ofSeconds(60), 5); }

    @Override
    public Optional<RuleHit> evaluate(RiskContext ctx) {
        var from = ctx.now().minus(window);
        long n = ctx.priorPayments().stream().filter(p -> !p.at().isBefore(from)).count();
        if (n >= maxAttempts)
            return Optional.of(new RuleHit("VELOCITY", 40, n + " attempts in last " + window.toSeconds() + "s"));
        return Optional.empty();
    }
}
