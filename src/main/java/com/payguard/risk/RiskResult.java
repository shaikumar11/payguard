package com.payguard.risk;

import java.util.List;

public record RiskResult(int score, Decision decision, List<RuleHit> hits) {
    public enum Decision { APPROVE, REVIEW, DECLINE }
}
