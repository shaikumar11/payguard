package com.payguard;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** A transfer request. Amounts are in minor units (cents) to avoid floating point. */
public record Transfer(String idempotencyKey, long fromAccount, long toAccount, long amountMinor,
                       String currency, String country, String deviceId) {

    public void validate() {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100)
            throw new IllegalArgumentException("Idempotency-Key is required (max 100 chars)");
        if (amountMinor <= 0) throw new IllegalArgumentException("amount must be positive");
        if (fromAccount == toAccount) throw new IllegalArgumentException("cannot transfer to the same account");
        if (currency == null || currency.length() != 3) throw new IllegalArgumentException("currency must be a 3 letter code");
        if (country == null || country.length() != 2) throw new IllegalArgumentException("country must be a 2 letter code");
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId is required");
    }

    /** Fingerprint of the business content, used to detect an idempotency key reused with a different body. */
    public String fingerprint() {
        String canonical = String.join("|", String.valueOf(fromAccount), String.valueOf(toAccount),
                String.valueOf(amountMinor), currency, country, deviceId);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
