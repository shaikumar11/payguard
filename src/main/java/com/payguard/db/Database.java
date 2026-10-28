package com.payguard.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Minimal blocking connection pool with a transaction helper. */
public final class Database implements AutoCloseable {
    @FunctionalInterface
    public interface TxWork<T> { T run(Connection c) throws SQLException; }

    private final BlockingQueue<Connection> pool;

    public Database(String url, String user, String password, int size) throws SQLException {
        pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            Connection c = DriverManager.getConnection(url, user, password);
            c.setAutoCommit(false);
            pool.add(c);
        }
    }

    public <T> T inTx(TxWork<T> work) throws SQLException {
        Connection c;
        try {
            c = pool.poll(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted waiting for a connection", e);
        }
        if (c == null) throw new SQLException("connection pool exhausted");
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            pool.add(c);
        }
    }

    @Override
    public void close() {
        for (Connection c : pool) {
            try { c.close(); } catch (SQLException ignored) { }
        }
    }
}
