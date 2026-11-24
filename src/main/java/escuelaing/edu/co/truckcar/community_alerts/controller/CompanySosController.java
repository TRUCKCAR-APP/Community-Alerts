package escuelaing.edu.co.truckcar.community_alerts.controller;

import escuelaing.edu.co.truckcar.community_alerts.dto.response.SosAlertResponse;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertUnauthenticatedException;
import escuelaing.edu.co.truckcar.community_alerts.exception.SosAccessDeniedException;
import escuelaing.edu.co.truckcar.community_alerts.service.CompanyDriversClient;
import escuelaing.edu.co.truckcar.community_alerts.service.SosService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * SOS abiertos (ACTIVE y ACKNOWLEDGED) de los conductores de una transportadora, para su pestaña «Mi flota». Solo los
 * de sus conductores con la afiliación ACTIVA; atenderlos sigue siendo de la torre de control.
 */
@RestController
@RequestMapping("/api/v1/alerts/sos")
@RequiredArgsConstructor
public class CompanySosController {

    private final SosService service;
    private final CompanyDriversClient companyDrivers;

    @GetMapping("/company")
    public List<SosAlertResponse> myCompanySos(
            @RequestHeader(value = "X-Auth-User", required = false) String userId,
            @RequestHeader(value = "X-Auth-Role", required = false) String role,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (userId == null || userId.isBlank()) {
            throw new AlertUnauthenticatedException("Se requiere un usuario autenticado");
        }
        if (!"TRANSPORTADORA".equals(role)) {
            throw new SosAccessDeniedException("Solo una cuenta de transportadora puede ver los SOS de su flota");
        }
        Set<String> drivers = companyDrivers.activeDriverIds(authorization);
        if (drivers.isEmpty()) {
            return List.of();
        }
        return service.list(null).stream()
                .filter(sos -> drivers.contains(sos.getDriverId()))
                .map(SosAlertResponse::of)
                .toList();
    }
}
