package escuelaing.edu.co.truckcar.community_alerts.controller;

import com.fasterxml.jackson.databind.JsonNode;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosCancelRequest;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosRequestReader;
import escuelaing.edu.co.truckcar.community_alerts.dto.response.SosAlertResponse;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertUnauthenticatedException;
import escuelaing.edu.co.truckcar.community_alerts.exception.SosAccessDeniedException;
import escuelaing.edu.co.truckcar.community_alerts.model.SosStatus;
import escuelaing.edu.co.truckcar.community_alerts.service.SosService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Botón SOS. Lo dispara solo un CONDUCTOR (el conductor es el del JWT, X-Auth-User); la torre de control
 * (COORDINADOR o ADMIN) consulta los activos y los atiende.
 */
@RestController
@RequestMapping("/api/v1/alerts/sos")
@RequiredArgsConstructor
public class SosController {

    private static final String AUTH_USER_HEADER = "X-Auth-User";
    private static final String AUTH_ROLE_HEADER = "X-Auth-Role";
    private static final Set<String> TOWER_ROLES = Set.of("COORDINADOR", "ADMIN");

    private final SosService service;

    /**
     * 202: el SOS quedó registrado y su evento en camino. Un reintento con el mismo clientEventId responde 200.
     * Nunca responde 400 por los datos del SOS: sin ubicación válida se completa en el servidor (ver SosService).
     */
    @PostMapping
    public ResponseEntity<SosAlertResponse> trigger(
            @RequestBody JsonNode body,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String userId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String role) {
        String driverId = requireUser(userId);
        if (!"CONDUCTOR".equals(role)) {
            throw new SosAccessDeniedException("Solo un conductor puede activar el botón SOS");
        }
        SosService.Result result = service.trigger(driverId, SosRequestReader.read(body), Instant.now());
        return new ResponseEntity<>(SosAlertResponse.of(result.sos()), result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK);
    }

    /** Sin status, las emergencias abiertas (ACTIVE y ACKNOWLEDGED), como antes. Ej.: ?status=ACTIVE */
    @GetMapping
    public List<SosAlertResponse> active(
            @RequestParam(name = "status", required = false) List<SosStatus> statuses,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String userId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String role) {
        requireTower(userId, role);
        return service.list(statuses).stream().map(SosAlertResponse::of).toList();
    }

    /** El conductor anula su propio SOS (falsa alarma). El cuerpo { "reason": "..." } es opcional. */
    @PatchMapping("/{sosId}/cancel")
    public SosAlertResponse cancel(
            @PathVariable Long sosId,
            @RequestBody(required = false) SosCancelRequest body,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String userId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String role) {
        String driverId = requireUser(userId);
        if (!"CONDUCTOR".equals(role)) {
            throw new SosAccessDeniedException("Solo el conductor que lo activó puede anular un SOS");
        }
        return SosAlertResponse.of(service.cancel(sosId, driverId, body == null ? null : body.getReason()));
    }

    @PatchMapping("/{sosId}/acknowledge")
    public SosAlertResponse acknowledge(
            @PathVariable Long sosId,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String userId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String role) {
        return SosAlertResponse.of(service.acknowledge(sosId, requireTower(userId, role)));
    }

    @PatchMapping("/{sosId}/resolve")
    public SosAlertResponse resolve(
            @PathVariable Long sosId,
            @RequestParam(required = false) String notes,
            @RequestHeader(value = AUTH_USER_HEADER, required = false) String userId,
            @RequestHeader(value = AUTH_ROLE_HEADER, required = false) String role) {
        return SosAlertResponse.of(service.resolve(sosId, requireTower(userId, role), notes));
    }

    private static String requireTower(String userId, String role) {
        String user = requireUser(userId);
        if (role == null || !TOWER_ROLES.contains(role)) {
            throw new SosAccessDeniedException("Solo la torre de control (COORDINADOR o ADMIN) gestiona los SOS");
        }
        return user;
    }

    private static String requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new AlertUnauthenticatedException("Se requiere un usuario autenticado");
        }
        return userId;
    }
}
