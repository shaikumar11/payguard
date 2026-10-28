package com.payguard.risk;

import java.util.Optional;

/** First time this account pays from a device, and the amount is meaningful. */
public record NewDeviceRule(long thresholdMinor) implements Rule {
    public NewDeviceRule() { this(100_000); }

    @Override
    public Optional<RuleHit> evaluate(RiskContext ctx) {
        var t = ctx.transfer();
        if (t.amountMinor() >= thresholdMinor && !ctx.knownDevices().contains(t.deviceId()))
            return Optional.of(new RuleHit("NEW_DEVICE", 30, "unseen device " + t.deviceId()));
        return Optional.empty();
    }
}
