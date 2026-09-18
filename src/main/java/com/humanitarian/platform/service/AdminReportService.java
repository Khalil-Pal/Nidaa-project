package com.humanitarian.platform.service;

import com.humanitarian.platform.repository.HelpRequestRepository;
import com.humanitarian.platform.repository.PsychologicalRequestRepository;
import com.humanitarian.platform.repository.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read models for the administrator screens (A-2): the combined request queue
 * (help and psychological requests joined to their people) and the statistics
 * panel. Moved out of AdminController so the web layer carries no SQL.
 *
 */
@Service
public class AdminReportService {

    private final JdbcTemplate jdbc;
    private final HelpRequestRepository helpRequestRepository;
    private final PsychologicalRequestRepository psychologicalRequestRepository;
    private final UserRepository userRepository;

    public AdminReportService(JdbcTemplate jdbc,
                              HelpRequestRepository helpRequestRepository,
                              PsychologicalRequestRepository psychologicalRequestRepository,
                              UserRepository userRepository) {
        this.jdbc = jdbc;
        this.helpRequestRepository = helpRequestRepository;
        this.psychologicalRequestRepository = psychologicalRequestRepository;
        this.userRepository = userRepository;
    }

    /** Every help and psychological request with requester and worker names, newest first per type. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> allRequests() {
        List<Map<String, Object>> result = new ArrayList<>();

        String helpSql = """
            SELECT hr.request_id AS id,
                   hr.title, hr.help_type, hr.urgency_level, hr.status,
                   hr.address, hr.created_at, hr.description,
                   hr.filed_by_user_id,
                   hr.needs_attention, hr.needs_attention_reason,
                   (SELECT count(*) FROM assignments a
                     WHERE a.request_id = hr.request_id AND a.status = 'DECLINED') AS declines,
                   ru.full_name  AS requester_name,
                   ru.email      AS requester_email,
                   ru.phone      AS requester_phone,
                   COALESCE(vusr.full_name, ousr.full_name) AS worker_name
            FROM help_requests hr
            LEFT JOIN users ru           ON ru.user_id        = hr.beneficiary_id
            LEFT JOIN volunteers v        ON v.volunteer_id    = hr.assigned_volunteer_id
            LEFT JOIN users vusr          ON vusr.user_id      = v.user_id
            LEFT JOIN organizations o     ON o.organization_id = hr.assigned_organization_id
            LEFT JOIN users ousr          ON ousr.user_id      = o.user_id
            ORDER BY hr.created_at DESC""";

        jdbc.queryForList(helpSql).forEach(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",             row.get("id"));
            item.put("requestType",    "HELP");
            item.put("title",          row.get("title"));
            item.put("helpType",       row.get("help_type"));
            item.put("urgencyLevel",   row.get("urgency_level"));
            item.put("status",         row.get("status"));
            item.put("address",        row.get("address"));
            item.put("createdAt",      row.get("created_at"));
            item.put("description",    row.get("description"));
            item.put("filedByUserId",  row.get("filed_by_user_id"));
            // GAP-1/GAP-2: what the admin queue has to look at, and why
            item.put("needsAttention", row.get("needs_attention"));
            item.put("attentionReason", row.get("needs_attention_reason"));
            item.put("declines",       row.get("declines"));
            item.put("requesterName",  nvl(row.get("requester_name"),  "—"));
            item.put("requesterEmail", nvl(row.get("requester_email"), "—"));
            item.put("requesterPhone", nvl(row.get("requester_phone"), "—"));
            item.put("workerName",     nvl(row.get("worker_name"),     "Not assigned yet"));
            result.add(item);
        });

        String psychSql = """
            SELECT pr.request_id AS id,
                   pr.category, pr.preferred_format, pr.urgency_level,
                   pr.status, pr.created_at, pr.description,
                   pr.is_crisis, pr.needs_review,
                   ru.full_name   AS requester_name,
                   ru.email       AS requester_email,
                   ru.phone       AS requester_phone,
                   pusr.full_name AS worker_name
            FROM psychological_requests pr
            LEFT JOIN users ru          ON ru.user_id        = pr.beneficiary_id
            LEFT JOIN psychologists p   ON p.psychologist_id = pr.assigned_psychologist_id
            LEFT JOIN users pusr        ON pusr.user_id      = p.user_id
            ORDER BY pr.created_at DESC""";

        jdbc.queryForList(psychSql).forEach(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id",             row.get("id"));
            item.put("requestType",    "PSYCHOLOGICAL");
            item.put("title",          nvl(row.get("category"), "Support") + " — " + nvl(row.get("preferred_format"), ""));
            item.put("helpType",       "PSYCHOLOGICAL");
            item.put("urgencyLevel",   row.get("urgency_level"));
            item.put("status",         row.get("status"));
            item.put("address",        null);
            item.put("createdAt",      row.get("created_at"));
            item.put("description",    row.get("description"));
            item.put("isCrisis",       row.get("is_crisis"));
            item.put("needsReview",    row.get("needs_review"));
            item.put("requesterName",  nvl(row.get("requester_name"),  "—"));
            item.put("requesterEmail", nvl(row.get("requester_email"), "—"));
            item.put("requesterPhone", nvl(row.get("requester_phone"), "—"));
            item.put("workerName",     nvl(row.get("worker_name"),     "Not assigned yet"));
            result.add(item);
        });

        return result;
    }

    /**
     * Statistics panel. Every number is a COUNT or GROUP BY in the database
     * (Q-2); nothing is loaded into memory to be counted. Request totals
     * combine help and psychological requests; user totals exclude anonymised
     * accounts, which are placeholders for aid history rather than people.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        helpRequestRepository.countGroupedByStatus().forEach(c -> byStatus.merge(nvl(c.key(), "UNKNOWN"), c.count(), Long::sum));
        psychologicalRequestRepository.countGroupedByStatus().forEach(c -> byStatus.merge(nvl(c.key(), "UNKNOWN"), c.count(), Long::sum));

        long helpCount  = helpRequestRepository.count();
        long psychCount = psychologicalRequestRepository.count();

        Map<String, Long> byType = new LinkedHashMap<>();
        helpRequestRepository.countGroupedByHelpType().forEach(c -> byType.merge(nvl(c.key(), "OTHER"), c.count(), Long::sum));
        byType.merge("PSYCHOLOGICAL", psychCount, Long::sum);

        Map<String, Long> byRegion = new LinkedHashMap<>();
        helpRequestRepository.countGroupedByAddress().forEach(c -> byRegion.put(c.key(), c.count()));

        long thisWeek = helpRequestRepository.countByCreatedAtAfter(weekAgo)
                + psychologicalRequestRepository.countByCreatedAtAfter(weekAgo);
        long completedThisWeek = helpRequestRepository.countByStatusAndCompletedAtAfter("COMPLETED", weekAgo)
                + psychologicalRequestRepository.countByStatusAndCompletedAtAfter("COMPLETED", weekAgo);

        Map<String, Long> usersByRole = new LinkedHashMap<>();
        userRepository.countActiveGroupedByRole().forEach(c -> usersByRole.put(c.role() != null ? c.role().name() : "UNKNOWN", c.count()));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRequests",     helpCount + psychCount);
        stats.put("byStatus",          byStatus);
        stats.put("byType",            byType);
        stats.put("byRegion",          byRegion);
        stats.put("thisWeek",          thisWeek);
        stats.put("completedThisWeek", completedThisWeek);
        stats.put("totalUsers",        userRepository.countByDeletedAtIsNull());
        stats.put("activeUsers",       userRepository.countByIsActiveTrue());
        stats.put("usersByRole",       usersByRole);
        return stats;
    }

    private static String nvl(Object v, String fallback) {
        return v != null ? v.toString() : fallback;
    }
}
