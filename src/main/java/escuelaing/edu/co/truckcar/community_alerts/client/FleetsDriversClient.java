package escuelaing.edu.co.truckcar.community_alerts.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Último estado conocido del conductor en fleets-drivers (GET /api/v1/fleets-drivers/drivers/{driverId}, llamada
 * interna sin X-Auth-User). Lo usa el SOS para completar la ubicación cuando el dispositivo no la envió y para tomar el
 * nombre y la placa del servidor en vez de los que manda el cliente.
 * <p>
 * Nunca lanza: si fleets-drivers no responde (timeout corto) o el conductor no tiene estado, devuelve vacío y el SOS
 * se registra igual.
 */
@Slf4j
@Component
public class FleetsDriversClient {

    private final RestClient restClient;
    private final ZoneId fleetsZone;

    public FleetsDriversClient(
            RestClient.Builder builder,
            @Value("${truckcar.services.fleets-drivers-url:http://localhost:8081}") String baseUrl,
            @Value("${truckcar.services.fleets-drivers-timeout-ms:1500}") int timeoutMs,
            @Value("${truckcar.services.fleets-drivers-zone:America/Bogota}") String fleetsZone) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restClient = builder.baseUrl(baseUrl.replaceAll("/+$", "")).requestFactory(factory).build();
        this.fleetsZone = ZoneId.of(fleetsZone);
    }

    /**
     * latitude/longitude son nulas si fleets-drivers no tiene posición; recordedAt es la hora de esa posición
     * (lastUpdate de fleets-drivers, sin zona: se interpreta en la zona de ese servicio).
     */
    public record DriverSnapshot(Double latitude, Double longitude, Instant recordedAt, String driverName,
                                 String vehiclePlate, Long currentLoadId) {

        public boolean hasPosition() {
            return latitude != null && longitude != null;
        }
    }

    public Optional<DriverSnapshot> lastKnown(String driverId) {
        if (driverId == null || driverId.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode state = restClient.get()
                    .uri("/api/v1/fleets-drivers/drivers/{driverId}", driverId)
                    .retrieve()
                    .body(JsonNode.class);
            if (state == null || !state.isObject()) {
                return Optional.empty();
            }
            Double latitude = number(state, "currentLatitude");
            Double longitude = number(state, "currentLongitude");
            boolean validPosition = latitude != null && longitude != null
                    && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
            return Optional.of(new DriverSnapshot(
                    validPosition ? latitude : null,
                    validPosition ? longitude : null,
                    validPosition ? instant(state, "lastUpdate") : null,
                    text(state, "driverName"),
                    text(state, "vehiclePlate"),
                    state.path("currentLoadId").canConvertToLong() ? state.get("currentLoadId").asLong() : null));
        } catch (HttpClientErrorException.NotFound e) {
            log.info("fleets-drivers no tiene estado del conductor {}", driverId);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Sin respuesta de fleets-drivers para el conductor {}: {}", driverId, e.getMessage());
            return Optional.empty();
        }
    }

    private static Double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fleets-drivers serializa LocalDateTime (sin zona).
        }
        try {
            return LocalDateTime.parse(value).atZone(fleetsZone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
