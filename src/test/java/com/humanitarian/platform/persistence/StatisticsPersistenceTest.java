package com.humanitarian.platform.persistence;

import com.humanitarian.platform.model.HelpRequest;
import com.humanitarian.platform.model.PsychologicalRequest;
import com.humanitarian.platform.model.User;
import com.humanitarian.platform.model.UserRole;
import com.humanitarian.platform.service.AdminReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q-2: the statistics panel is computed by COUNT and GROUP BY queries. The
 * grouped columns are PostgreSQL enums read through String fields, so the
 * queries are exercised against the real schema. Totals are compared
 * relative to the rows this test adds because the test database is shared.
 */
@EnabledIf(value = PersistenceTestSupport.CONDITION, disabledReason = "nidaa_test database not reachable")
@Import(AdminReportService.class)
class StatisticsPersistenceTest extends PersistenceTestSupport {

    @Autowired private AdminReportService reportService;

    @SuppressWarnings("unchecked")
    private static Map<String, Long> group(Map<String, Object> stats, String key) {
        return (Map<String, Long>) stats.get(key);
    }

    @Test
    void statisticsAreAggregatedInTheDatabase() {
        Map<String, Object> before = reportService.stats();

        User beneficiary = newUser(UserRole.BENEFICIARY, "stats-bene@example.test");
        newUser(UserRole.VOLUNTEER, "stats-vol@example.test");
        User inactive = newUser(UserRole.VOLUNTEER, "stats-inactive@example.test");
        inactive.setIsActive(false);
        em.persistAndFlush(inactive);

        HelpRequest a = newHelpRequest(beneficiary.getId(), "FOOD", "HIGH", "PENDING");
        a.setAddress("  Q2 Region A ");
        HelpRequest b = newHelpRequest(beneficiary.getId(), "FOOD", "LOW", "COMPLETED");
        b.setAddress("Q2 Region A");
        b.setCompletedAt(LocalDateTime.now().minusDays(1));
        HelpRequest c = newHelpRequest(beneficiary.getId(), "WATER", "LOW", "COMPLETED");
        c.setAddress("Q2 Region B");
        c.setCompletedAt(LocalDateTime.now().minusDays(30));   // completed, but not this week
        em.persistAndFlush(a); em.persistAndFlush(b); em.persistAndFlush(c);

        em.persistAndFlush(PsychologicalRequest.builder()
                .beneficiaryId(beneficiary.getId())
                .category("ANXIETY").supportType("INDIVIDUAL").urgencyLevel("MEDIUM").preferredFormat("CHAT")
                .description("stats").status("PENDING")
                .build());
        em.flush(); em.clear();

        Map<String, Object> after = reportService.stats();

        assertEquals((long) before.get("totalRequests") + 4, after.get("totalRequests"));
        assertEquals(group(before, "byStatus").getOrDefault("PENDING", 0L) + 2, group(after, "byStatus").get("PENDING"));
        assertEquals(group(before, "byStatus").getOrDefault("COMPLETED", 0L) + 2, group(after, "byStatus").get("COMPLETED"));
        assertEquals(group(before, "byType").getOrDefault("FOOD", 0L) + 2, group(after, "byType").get("FOOD"));
        assertEquals(group(before, "byType").getOrDefault("PSYCHOLOGICAL", 0L) + 1, group(after, "byType").get("PSYCHOLOGICAL"));
        assertEquals(2L, group(after, "byRegion").get("Q2 Region A"), "addresses are trimmed before grouping");
        assertEquals(1L, group(after, "byRegion").get("Q2 Region B"));
        assertEquals((long) before.get("thisWeek") + 4, after.get("thisWeek"));
        assertEquals((long) before.get("completedThisWeek") + 1, after.get("completedThisWeek"));
        assertEquals((long) before.get("totalUsers") + 3, after.get("totalUsers"));
        assertEquals((long) before.get("activeUsers") + 2, after.get("activeUsers"));
        assertEquals(group(before, "usersByRole").getOrDefault("VOLUNTEER", 0L) + 1, group(after, "usersByRole").get("VOLUNTEER"),
                "only active accounts are counted per role");
        assertTrue(group(after, "usersByRole").containsKey("BENEFICIARY"));
        assertFalse(group(after, "byStatus").containsKey("UNKNOWN"));
    }
}
