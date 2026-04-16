package com.humanitarian.platform.repository;

import com.humanitarian.platform.model.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {
    List<Location> findByCityIgnoreCase(String city);
    List<Location> findByCountryIgnoreCase(String country);

    @Query(value = "SELECT * FROM locations l WHERE " +
            "(6371 * acos(cos(radians(:lat)) * cos(radians(l.latitude)) * " +
            "cos(radians(l.longitude) - radians(:lng)) + " +
            "sin(radians(:lat)) * sin(radians(l.latitude)))) <= :radius",
            nativeQuery = true)
    List<Location> findNearbyLocations(
            @Param("lat") double latitude,
            @Param("lng") double longitude,
            @Param("radius") double radiusKm);
}