package com.humanitarian.platform.persistence;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base for tests that run against a real PostgreSQL schema (T-2).
 *
 * <p>The target is the {@code nidaa_test} database, created from the
 * migrations alone (see README "Testing"). Each test runs in a transaction
 * that is rolled back, so the database stays empty between runs. When the
 * database is not reachable the subclasses are skipped, not failed: they
 * carry {@code @EnabledIf} pointing at {@link #testDatabaseAvailable()}.
 *
 * <p>Why a real database: the D-1 enum mismatch, the phone NOT NULL failure
 * and the cascading user delete all passed every mocked test in the project.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=${NIDAA_TEST_DB_URL:jdbc:postgresql://localhost:5432/nidaa_test?stringtype=unspecified}",
        "spring.jpa.show-sql=false",
        "app.ratelimit.enabled=false"
})
abstract class PersistenceTestSupport {

    static final String CONDITION = "com.humanitarian.platform.persistence.PersistenceTestSupport#testDatabaseAvailable";

    private static final AtomicReference<Boolean> AVAILABLE = new AtomicReference<>();

    @Autowired protected TestEntityManager em;

    /** Evaluated once by JUnit before any Spring context is built. */
    static boolean testDatabaseAvailable() {
        Boolean cached = AVAILABLE.get();
        if (cached != null) return cached;
        String url = System.getenv().getOrDefault("NIDAA_TEST_DB_URL",
                "jdbc:postgresql://localhost:5432/nidaa_test?stringtype=unspecified");
        String user = System.getenv().getOrDefault("DB_USERNAME", "postgres");
        String password = System.getenv("DB_PASSWORD");
        if (password == null) password = readDotEnv("DB_PASSWORD");
        boolean ok;
        try (var c = DriverManager.getConnection(url, user, password == null ? "" : password)) {
            ok = c.isValid(2);
        } catch (Exception e) {
            ok = false;
            System.out.println("[persistence tests] skipped: " + url + " not reachable (" + e.getMessage() + ")");
        }
        AVAILABLE.set(ok);
        return ok;
    }

    // Mirrors spring.config.import=optional:file:.env[.properties] for the pre-context check.
    private static String readDotEnv(String key) {
        Path env = Path.of(".env");
        if (!Files.exists(env)) return null;
        try {
            for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key + "=")) return trimmed.substring(key.length() + 1).trim();
            }
        } catch (IOException ignored) {
            // fall through: treat as not configured
        }
        return null;
    }

    // -- fixtures ---------------------------------------------------------------

    protected User newUser(UserRole role, String email) {
        return em.persistAndFlush(User.builder()
                .email(email)
                .passwordHash("$2a$10$test-hash-not-a-real-password-hash-xxxxxxxxxx")
                .fullName("Test " + role.name().toLowerCase())
                .phone("+1000000000")
                .role(role)
                .isVerified(true)
                .isActive(true)
                .isLocked(false)
                .build());
    }

    protected HelpRequest newHelpRequest(Long beneficiaryId, String helpType, String urgency, String status) {
        return em.persistAndFlush(HelpRequest.builder()
                .beneficiaryId(beneficiaryId)
                .title("Persistence test request")
                .description("Persistence test request")
                .helpType(helpType)
                .urgencyLevel(urgency)
                .status(status)
                .peopleCount(2)
                .priorityScore(50)
                .build());
    }
}
