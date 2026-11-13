package escuelaing.edu.co.truckcar.community_alerts.dto.request;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Lee un SOS de un JSON sin rechazarlo nunca: un campo con un tipo inesperado (latitud "abc", loadId "x") se ignora en
 * vez de devolver 400. Lo usan el POST en línea y la cola offline (/sync/batch, kind=alert.sos).
 */
public final class SosRequestReader {

    private SosRequestReader() {
    }

    public static SosRequest read(JsonNode body) {
        if (body == null || !body.isObject()) {
            return new SosRequest();
        }
        return SosRequest.builder()
                .latitude(number(body, "latitude"))
                .longitude(number(body, "longitude"))
                .accuracyMeters(number(body, "accuracyMeters"))
                .loadId(longValue(body, "loadId"))
                .vehiclePlate(text(body, "vehiclePlate"))
                .driverName(text(body, "driverName"))
                .message(text(body, "message"))
                .clientEventId(uuid(body, "clientEventId"))
                .build();
    }

    private static Double number(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isTextual()) {
            try {
                return Double.valueOf(value.asText().trim().replace(',', '.'));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long longValue(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.canConvertToLong() && (value.isIntegralNumber() || value.isTextual())) {
            try {
                return value.isTextual() ? Long.valueOf(value.asText().trim()) : value.asLong();
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() || value.isContainerNode() ? null : value.asText();
    }

    private static UUID uuid(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
