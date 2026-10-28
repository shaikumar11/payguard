package com.payguard.risk;

public record RuleHit(String rule, int points, String detail) {
    public String describe() { return rule + "(+" + points + "): " + detail; }
}
