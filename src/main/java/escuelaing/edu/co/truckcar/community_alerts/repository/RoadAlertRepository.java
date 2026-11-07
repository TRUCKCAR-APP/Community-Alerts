package escuelaing.edu.co.truckcar.community_alerts.repository;

import escuelaing.edu.co.truckcar.community_alerts.model.RoadAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface RoadAlertRepository extends JpaRepository<RoadAlert, Long> {

    Optional<RoadAlert> findByClientAlertId(String clientAlertId);

    @Query(value = """
        SELECT * FROM road_alerts a
        WHERE a.is_active = true
          AND a.alert_type = :alertType
          AND a.reported_at >= :since
          AND ST_DWithin(
              a.location::geography,
              ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
              :radiusMeters
          )
        ORDER BY a.reported_at DESC
        LIMIT 1
        """, nativeQuery = true)
    Optional<RoadAlert> findRecentDuplicate(
            @Param("alertType") String alertType,
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("since") LocalDateTime since,
            @Param("radiusMeters") double radiusMeters
    );

    @Query(value = """
        SELECT * FROM road_alerts a
        WHERE a.is_active = true
        AND ST_DWithin(
            a.location::geography,
            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
            :radiusMeters
        )
        ORDER BY a.reported_at DESC
        """, nativeQuery = true)
    List<RoadAlert> findActiveAlertsWithinRadius(
            @Param("latitude") double latitude,
            @Param("longitude") double longitude,
            @Param("radiusMeters") double radiusMeters
    );

    List<RoadAlert> findByIsActiveTrueOrderByReportedAtDesc();

    /** Candidatas a vencer: activas y reportadas antes del TTL más corto. */
    List<RoadAlert> findByIsActiveTrueAndReportedAtBefore(LocalDateTime cutoff);

    /** Desactiva solo si sigue activa: con varias réplicas, una sola gana y publica alert.expired. */
    @Modifying
    @Query("UPDATE RoadAlert a SET a.isActive = false WHERE a.id = :id AND a.isActive = true")
    int deactivateIfActive(@Param("id") Long id);

    @Modifying
    @Query("UPDATE RoadAlert a SET a.confirmationsCount = a.confirmationsCount + 1 WHERE a.id = :id")
    int incrementConfirmation(@Param("id") Long id);
}