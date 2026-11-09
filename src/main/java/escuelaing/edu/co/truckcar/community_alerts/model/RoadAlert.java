package escuelaing.edu.co.truckcar.community_alerts.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "road_alerts", indexes = {
        @Index(name = "idx_alert_type", columnList = "alert_type"),
        @Index(name = "idx_alert_status", columnList = "is_active")
})
public class RoadAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_alert_id", nullable = true, unique = true)
    private String clientAlertId;

    @Column(name = "driver_id", nullable = false)
    private String driverId;

    @Column(name = "driver_name")
    private String driverName;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private AlertSeverity severity;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "road_corridor")
    private String roadCorridor;

    @Column(name = "max_clearance_meters")
    private Double maxClearanceMeters;

    @Column(columnDefinition = "geometry(Point, 4326)", nullable = false)
    private Point location;

    private Double latitude;
    private Double longitude;

    @Builder.Default
    @Column(name = "confirmations_count")
    private Integer confirmationsCount = 1;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "reported_at", nullable = false)
    private LocalDateTime reportedAt;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolution_notes", length = 500)
    private String resolutionNotes;
}