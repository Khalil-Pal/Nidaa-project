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
import java.util.stream.Collectors;

/**
 * Read models for the administrator screens (A-2): the combined request queue
 * (help and psychological requests joined to their people) and the statistics
 * panel. Moved out of AdminController so the web layer carries no SQL.
 *
 * The statistics still aggregate in Java over full table loads; Q-2 replaces
 * them with GROUP BY queries.
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

    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        var helpRequests  = helpRequestRepository.findAll();
        var psychRequests = psychologicalRequestRepository.findAll();
        var allUsers      = userRepository.findAll();
        var weekAgo       = LocalDateTime.now().minusDays(7);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        helpRequests.forEach(r -> byStatus.merge(r.getStatus() != null ? r.getStatus() : "UNKNOWN", 1L, Long::sum));
        psychRequests.forEach(r -> byStatus.merge(r.getStatus() != null ? r.getStatus() : "UNKNOWN", 1L, Long::sum));

        Map<String, Long> byType = new LinkedHashMap<>();
        helpRequests.forEach(r -> byType.merge(r.getHelpType() != null ? r.getHelpType() : "OTHER", 1L, Long::sum));
        byType.merge("PSYCHOLOGICAL", (long) psychRequests.size(), Long::sum);

        Map<String, Long> byRegion = helpRequests.stream()
                .filter(r -> r.getAddress() != null && !r.getAddress().isBlank())
                .collect(Collectors.groupingBy(r -> r.getAddress().trim(), Collectors.counting()));

        long thisWeek = helpRequests.stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(weekAgo)).count()
                + psychRequests.stream()
                .filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(weekAgo)).count();

        long completedThisWeek = helpRequests.stream()
                .filter(r -> "COMPLETED".equals(r.getStatus()) && r.getCompletedAt() != null
                        && r.getCompletedAt().isAfter(weekAgo)).count()
                + psychRequests.stream()
                .filter(r -> "COMPLETED".equals(r.getStatus()) && r.getCompletedAt() != null
                        && r.getCompletedAt().isAfter(weekAgo)).count();

        Map<String, Long> usersByRole = allUsers.stream().filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .collect(Collectors.groupingBy(u -> u.getRole() != null ? u.getRole().name() : "UNKNOWN", Collectors.counting()));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRequests",     helpRequests.size() + psychRequests.size());
        stats.put("byStatus",          byStatus);
        stats.put("byType",            byType);
        stats.put("byRegion",          byRegion);
        stats.put("thisWeek",          thisWeek);
        stats.put("completedThisWeek", completedThisWeek);
        stats.put("totalUsers",        allUsers.size());
        stats.put("activeUsers",       allUsers.stream().filter(u -> Boolean.TRUE.equals(u.getIsActive())).count());
        stats.put("usersByRole",       usersByRole);
        return stats;
    }

    private static String nvl(Object v, String fallback) {
        return v != null ? v.toString() : fallback;
    }
}
