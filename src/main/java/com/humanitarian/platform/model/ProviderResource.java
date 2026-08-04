package com.humanitarian.platform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "provider_resources")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderResource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "help_type", nullable = false, columnDefinition = "help_type")
    private String helpType;

    @Column(name = "capacity_mode", nullable = false, length = 20)
    private String capacityMode;

    @Column(name = "capacity_amount")
    private Integer capacityAmount;

    @Column(name = "capacity_label", length = 50)
    private String capacityLabel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
