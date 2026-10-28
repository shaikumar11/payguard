package com.payguard.risk;

import java.util.Optional;

public sealed interface Rule permits HighAmountRule, VelocityRule, CountryHopRule, NewDeviceRule {
    Optional<RuleHit> evaluate(RiskContext ctx);
}
