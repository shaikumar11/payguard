package com.payguard;

import com.payguard.api.Server;
import com.payguard.db.Database;
import com.payguard.db.Schema;
import com.payguard.risk.RiskEngine;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;

public final class Main {
    public static void main(String[] args) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String url = env("PG_URL", "jdbc:postgresql://localhost:5432/payguard");
        Database db = new Database(url, env("PG_USER", "payguard"), env("PG_PASSWORD", "payguard"), 10);
        Schema.create(db);
        if ("true".equals(env("SEED", "true"))) seed(db);
        var service = new TransferService(db, RiskEngine.defaults(), Clock.systemUTC());
        var server = new Server(service, Integer.parseInt(env("PORT", "8080")));
        server.start();
        System.out.println("PayGuard listening on :" + server.port());
    }

    private static void seed(Database db) throws Exception {
        db.inTx(c -> {
            try (var st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM accounts")) {
                rs.next();
                if (rs.getLong(1) > 0) return null;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO accounts(id, owner, currency, balance) VALUES (?,?,'GBP',?)")) {
                String[] owners = {"alice", "bob", "carol"};
                for (int i = 0; i < owners.length; i++) {
                    ps.setLong(1, i + 1); ps.setString(2, owners[i]); ps.setLong(3, 5_000_000);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}

