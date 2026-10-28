package com.payguard.risk;

import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;

/** Payment from a different country than the previous one, too soon to be physically plausible. */
public record CountryHopRule(Duration window) implements Rule {
    public CountryHopRule() { this(Duration.ofMinutes(10)); }

    @Override
    public Optional<RuleHit> evaluate(RiskContext ctx) {
        var from = ctx.now().minus(window);
        return ctx.priorPayments().stream()
                .filter(p -> !p.at().isBefore(from))
                .max(Comparator.comparing(RiskContext.PriorPayment::at))
                .filter(p -> !p.country().equalsIgnoreCase(ctx.transfer().country()))
                .map(p -> new RuleHit("COUNTRY_HOP", 45,
                        p.country() + " to " + ctx.transfer().country() + " within " + window.toMinutes() + "m"));
    }
}
