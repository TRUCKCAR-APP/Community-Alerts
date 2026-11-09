package escuelaing.edu.co.truckcar.community_alerts.dto.response;

import escuelaing.edu.co.truckcar.community_alerts.model.AlertSeverity;
import escuelaing.edu.co.truckcar.community_alerts.model.AlertType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoadAlertResponse {
    private Long id;
    private String clientAlertId;
    private String driverId;
    private String driverName;
    private AlertType alertType;
    private AlertSeverity severity;
    private String title;
    private String description;
    private String roadCorridor;
    private Double maxClearanceMeters;
    private Double latitude;
    private Double longitude;
    private Integer confirmationsCount;
    private Boolean isActive;
    private LocalDateTime reportedAt;
    private LocalDateTime syncedAt;
    private LocalDateTime resolvedAt;
    private String resolvedBy;
    private String resolutionNotes;
}