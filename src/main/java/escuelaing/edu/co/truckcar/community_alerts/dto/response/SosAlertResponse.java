package escuelaing.edu.co.truckcar.community_alerts.dto.response;

import escuelaing.edu.co.truckcar.community_alerts.model.SosAlert;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class SosAlertResponse {

    private Long id;
    private String driverId;
    private String driverName;
    private String vehiclePlate;
    private Long loadId;
    private Double latitude;
    private Double longitude;
    private Double accuracyMeters;
    /** DEVICE_GPS, LAST_KNOWN (última posición en fleets-drivers) o UNKNOWN (latitude/longitude nulas). */
    private String locationSource;
    /** Hora de la posición LAST_KNOWN; nula en los demás casos. */
    private Instant locationRecordedAt;
    private String message;
    private String status;
    private Instant triggeredAt;
    private Instant receivedAt;
    private String acknowledgedBy;
    private Instant acknowledgedAt;
    private String resolvedBy;
    private Instant resolvedAt;
    private String resolutionNotes;
    private Instant cancelledAt;
    private String cancellationReason;

    public static SosAlertResponse of(SosAlert sos) {
        return SosAlertResponse.builder()
                .id(sos.getId())
                .driverId(sos.getDriverId())
                .driverName(sos.getDriverName())
                .vehiclePlate(sos.getVehiclePlate())
                .loadId(sos.getLoadId())
                .latitude(sos.getLatitude())
                .longitude(sos.getLongitude())
                .accuracyMeters(sos.getAccuracyMeters())
                .locationSource(sos.getLocationSource() == null ? null : sos.getLocationSource().name())
                .locationRecordedAt(sos.getLocationRecordedAt())
                .message(sos.getMessage())
                .status(sos.getStatus().name())
                .triggeredAt(sos.getTriggeredAt())
                .receivedAt(sos.getReceivedAt())
                .acknowledgedBy(sos.getAcknowledgedBy())
                .acknowledgedAt(sos.getAcknowledgedAt())
                .resolvedBy(sos.getResolvedBy())
                .resolvedAt(sos.getResolvedAt())
                .resolutionNotes(sos.getResolutionNotes())
                .cancelledAt(sos.getCancelledAt())
                .cancellationReason(sos.getCancellationReason())
                .build();
    }
}
