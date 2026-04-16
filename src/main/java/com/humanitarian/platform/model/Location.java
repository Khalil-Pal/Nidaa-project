package com.humanitarian.platform.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.LocalDateTime;

@Entity
@Table(name = "locations")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Location {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "location_id")
    private Long id;

    @Column(name = "country")
    private String country;

    @Column(name = "region")
    private String region;

    @Column(name = "city")
    private String city;

    @Column(name = "district")
    private String district;

    @Column(name = "latitude", nullable = false, columnDefinition = "numeric")
    private Double latitude;

    @Column(name = "longitude", nullable = false, columnDefinition = "numeric")
    private Double longitude;

    @Column(name = "place_name")
    private String placeName;

    @Column(name = "population_estimate")
    private Integer populationEstimate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}