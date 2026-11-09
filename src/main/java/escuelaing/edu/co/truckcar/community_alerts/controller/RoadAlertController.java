package escuelaing.edu.co.truckcar.community_alerts.controller;

import escuelaing.edu.co.truckcar.community_alerts.dto.request.CreateRoadAlertRequest;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.OfflineBatchSyncRequest;
import escuelaing.edu.co.truckcar.community_alerts.dto.response.RoadAlertResponse;
import escuelaing.edu.co.truckcar.community_alerts.dto.response.SyncSummaryResponse;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertUnauthenticatedException;
import escuelaing.edu.co.truckcar.community_alerts.service.IRoadAlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RoadAlertController {

    private static final String AUTH_USER_HEADER = "X-Auth-User";
    /** Radio máximo de /nearby: evita consultas PostGIS sobre todo el país. */
    private static final int MAX_RADIUS_METERS = 200_000;

    private final IRoadAlertService service;

    /** El autor es siempre el usuario del JWT (X-Auth-User del gateway); el driverId del cuerpo se ignora. */
    @PostMapping
    public ResponseEntity<RoadAlertResponse> createAlert(
            @Valid @RequestBody CreateRoadAlertRequest request,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId) {
        request.setDriverId(requireUser(authenticatedUserId));
        return new ResponseEntity<>(service.createAlert(request), HttpStatus.CREATED);
    }

    @PostMapping("/sync-offline")
    public ResponseEntity<SyncSummaryResponse> syncOfflineBatch(
            @Valid @RequestBody OfflineBatchSyncRequest request,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId) {
        String userId = requireUser(authenticatedUserId);
        request.setDriverId(userId);
        request.getAlerts().forEach(alert -> alert.setDriverId(userId));
        return ResponseEntity.ok(service.syncOfflineBatch(request));
    }

    /** Un voto por usuario: 409 si ya confirmó o si es quien reportó la alerta. */
    @PatchMapping("/{alertId}/confirm")
    public ResponseEntity<RoadAlertResponse> confirmAlert(
            @PathVariable Long alertId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId) {
        return ResponseEntity.ok(service.confirmAlert(alertId, requireUser(authenticatedUserId)));
    }

    /** Cierra/resuelve la novedad alertada (tráfico despejado, derrumbe removido, etc.). */
    @PatchMapping("/{alertId}/resolve")
    public ResponseEntity<RoadAlertResponse> resolveAlert(
            @PathVariable Long alertId,
            @RequestParam(required = false) String notes,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String authenticatedUserId) {
        return ResponseEntity.ok(service.resolveAlert(alertId, requireUser(authenticatedUserId), notes));
    }

    @GetMapping("/nearby")
    public ResponseEntity<List<RoadAlertResponse>> getNearbyAlerts(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "50000") @Positive @Max(MAX_RADIUS_METERS) double radiusMeters) {
        return ResponseEntity.ok(service.getNearbyAlerts(latitude, longitude, radiusMeters));
    }

    private static String requireUser(String authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId.isBlank()) {
            throw new AlertUnauthenticatedException("Se requiere un usuario autenticado para reportar o confirmar alertas");
        }
        return authenticatedUserId;
    }

    @GetMapping
    public ResponseEntity<List<RoadAlertResponse>> getAllActive() {
        return ResponseEntity.ok(service.getAllActiveAlerts());
    }
}