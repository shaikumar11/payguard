package com.payguard.risk;

import com.payguard.Transfer;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Everything a rule may look at. Prior attempts exclude the payment being evaluated. */
public record RiskContext(Transfer transfer, Instant now, List<PriorPayment> priorPayments, Set<String> knownDevices) {
    public record PriorPayment(Instant at, String country) {}
}
