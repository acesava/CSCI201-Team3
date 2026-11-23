package edu.usc.csci201.team3;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * A tiny bounded connection pool. Connections survive between warm Lambda invocations,
 * and worker threads each borrow their own connection because JDBC connections are not thread-safe.
 */
final class Database {
    interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Thrown when the database cannot be reached; the handler turns it into a 503. */
    static final class UnavailableException extends RuntimeException {
        UnavailableException(Throwable cause) {
            super(cause.getClass().getSimpleName(), cause, false, false);
        }
    }

    static final int MAX_CONNECTIONS = 4;
    private final ConnectionFactory factory;
    private final BlockingQueue<Connection> idle = new ArrayBlockingQueue<>(MAX_CONNECTIONS);
    private final Semaphore permits = new Semaphore(MAX_CONNECTIONS);

    Database(ConnectionFactory factory) {
        this.factory = factory;
    }

    /**
     * DATABASE_URL is a JDBC URL such as jdbc:postgresql://HOST:6543/postgres?sslmode=require.
     * Supabase's transaction pooler does not support server-side prepared statements, hence prepareThreshold=0.
     */
    static Database fromEnvironment() {
        String url = System.getenv("DATABASE_URL");
        String user = System.getenv("DATABASE_USER");
        String password = System.getenv("DATABASE_PASSWORD");
        return new Database(() -> {
            if (url == null || url.isBlank()) throw new SQLException("DATABASE_URL is required");
            Properties props = new Properties();
            if (user != null) props.setProperty("user", user);
            if (password != null) props.setProperty("password", password);
            props.setProperty("connectTimeout", "5");
            props.setProperty("socketTimeout", "15");
            props.setProperty("prepareThreshold", "0");
            props.setProperty("ApplicationName", "csci201-team3-api");
            return DriverManager.getConnection(url, props);
        });
    }

    <T> T read(SqlWork<T> work) {
        return withConnection(connection -> {
            connection.setAutoCommit(true);
            return work.run(connection);
        });
    }

    <T> T transaction(SqlWork<T> work) {
        return withConnection(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        });
    }

    private <T> T withConnection(SqlWork<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UnavailableException(interrupted);
        }
        if (!acquired) throw new UnavailableException(new IllegalStateException("pool exhausted"));
        Connection connection = null;
        boolean healthy = false;
        try {
            connection = borrow();
            T result = work.run(connection);
            healthy = true;
            return result;
        } catch (SQLException failure) {
            // Constraint violations mean the connection is still fine; anything else, discard it.
            healthy = failure.getSQLState() != null && failure.getSQLState().startsWith("23");
            throw new UnavailableException(failure);
        } catch (ApiException expected) {
            healthy = true;
            throw expected;
        } finally {
            release(connection, healthy);
            permits.release();
        }
    }

    private Connection borrow() throws SQLException {
        Connection pooled;
        while ((pooled = idle.poll()) != null) {
            if (pooled.isValid(2)) return pooled;
            closeQuietly(pooled);
        }
        return factory.open();
    }

    private void release(Connection connection, boolean healthy) {
        if (connection == null) return;
        if (!healthy || !idle.offer(connection)) closeQuietly(connection);
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Already broken; nothing else to do.
        }
    }
}
