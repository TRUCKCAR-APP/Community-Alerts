package escuelaing.edu.co.truckcar.community_alerts.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Alerta de pánico (botón SOS) de un conductor. */
@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "sos_alerts", indexes = @Index(name = "idx_sos_alerts_active", columnList = "status, triggered_at"))
public class SosAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id", unique = true)
    private UUID clientEventId;

    @Column(name = "driver_id", nullable = false)
    private String driverId;

    @Column(name = "driver_name", length = 150)
    private String driverName;

    @Column(name = "vehicle_plate", length = 20)
    private String vehiclePlate;

    @Column(name = "load_id")
    private Long loadId;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(name = "accuracy_meters")
    private Double accuracyMeters;

    @Column(length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SosStatus status;

    /** Hora del dispositivo: un SOS guardado sin señal conserva el momento en que se pidió ayuda. */
    @Column(name = "triggered_at", nullable = false)
    private Instant triggeredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_notes", length = 500)
    private String resolutionNotes;
}
