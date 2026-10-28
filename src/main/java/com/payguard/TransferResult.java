package com.payguard;

import java.util.List;

public record TransferResult(long paymentId, Outcome outcome, int riskScore, List<String> reasons, boolean replayed) {
    public TransferResult asReplay() {
        return new TransferResult(paymentId, outcome, riskScore, reasons, true);
    }
}
