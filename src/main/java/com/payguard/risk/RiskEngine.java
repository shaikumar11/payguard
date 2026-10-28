package com.payguard.risk;

import java.util.List;

public final class RiskEngine {
    private final List<Rule> rules;
    private final int reviewAt;
    private final int declineAt;

    public RiskEngine(List<Rule> rules, int reviewAt, int declineAt) {
        this.rules = List.copyOf(rules);
        this.reviewAt = reviewAt;
        this.declineAt = declineAt;
    }

    public static RiskEngine defaults() {
        return new RiskEngine(List.of(new HighAmountRule(), new VelocityRule(), new CountryHopRule(), new NewDeviceRule()), 40, 80);
    }

    public RiskResult evaluate(RiskContext ctx) {
        List<RuleHit> hits = rules.stream().map(r -> r.evaluate(ctx)).flatMap(java.util.Optional::stream).toList();
        int score = Math.min(100, hits.stream().mapToInt(RuleHit::points).sum());
        var decision = score >= declineAt ? RiskResult.Decision.DECLINE
                : score >= reviewAt ? RiskResult.Decision.REVIEW : RiskResult.Decision.APPROVE;
        return new RiskResult(score, decision, hits);
    }
}
