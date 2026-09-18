package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "help_requests")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HelpRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long id;

    @Column(name = "beneficiary_id", nullable = false)
    private Long beneficiaryId;

    // Volunteer or organization that filed this request on the beneficiary's
    // behalf; NULL when the beneficiary filed it themselves (V13).
    @Column(name = "filed_by_user_id")
    private Long filedByUserId;

    // Not a column. Set by HelpRequestService only on a request filed on
    // someone's behalf and only for a viewer already entitled to the name
    // (the beneficiary, the filer, the assigned provider, an admin), so the
    // "Filed on behalf of <name>" badge can be shown (ON-2); absent otherwise.
    @Transient
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private String beneficiaryName;

    @Column(name = "title")
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "help_type", columnDefinition = "help_type")
    private String helpType;

    @Column(name = "urgency_level", columnDefinition = "urgency_level")
    private String urgencyLevel;

    @Column(name = "priority_score")
    @Builder.Default
    private Integer priorityScore = 0;

    @Column(name = "people_count")
    @Builder.Default
    private Integer peopleCount = 1;

    @Column(name = "has_children")
    @Builder.Default
    private Boolean hasChildren = false;

    @Column(name = "has_elderly")
    @Builder.Default
    private Boolean hasElderly = false;

    @Column(name = "has_disabled")
    @Builder.Default
    private Boolean hasDisabled = false;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "latitude", columnDefinition = "numeric")
    private Double latitude;

    @Column(name = "longitude", columnDefinition = "numeric")
    private Double longitude;

    @Column(name = "status", columnDefinition = "help_request_status")
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "assigned_volunteer_id")
    private Long assignedVolunteerId;

    @Column(name = "assigned_organization_id")
    private Long assignedOrganizationId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "TEXT")
    private String cancellationReason;

    // GAP-1/GAP-2: an administrator has to look at this one — three providers
    // declined it, or it waited past the escalation age with nobody to take it.
    // Cleared when the request is finally assigned.
    @Column(name = "needs_attention", nullable = false)
    @Builder.Default
    private Boolean needsAttention = false;

    @Column(name = "needs_attention_at")
    private LocalDateTime needsAttentionAt;

    @Column(name = "needs_attention_reason", columnDefinition = "TEXT")
    private String needsAttentionReason;
}