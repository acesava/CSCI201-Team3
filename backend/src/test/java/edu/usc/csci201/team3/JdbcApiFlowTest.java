package edu.usc.csci201.team3;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Optional integration test: every ApiFlowTest scenario against a disposable local Postgres
 * with database/migrations applied. Example:
 * POSTGRES_TEST_URL=jdbc:postgresql://127.0.0.1:55432/postgres?user=postgres ./mvnw verify
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class JdbcApiFlowTest extends ApiFlowTest {
    @Override Repository newRepository() {
        String url = System.getenv("POSTGRES_TEST_URL");
        String host = URI.create(url.substring("jdbc:".length())).getHost();
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            throw new IllegalStateException("This test truncates every table; it must only use a local disposable database");
        }
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            s.execute("truncate public.users, public.sessions, public.participants, public.expenses, public.expense_items,"
                    + " public.item_allocations, public.expense_payments, public.expense_shares, public.settlements,"
                    + " public.monthly_summaries, public.usage_counters");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        return new JdbcRepository(new Database(() -> DriverManager.getConnection(url)));
    }
}
