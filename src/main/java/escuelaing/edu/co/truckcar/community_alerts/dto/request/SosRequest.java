package escuelaing.edu.co.truckcar.community_alerts.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Pedido de auxilio del conductor. El conductor es siempre el del JWT (X-Auth-User). clientEventId (opcional) hace
 * idempotente el reenvío: el mismo SOS repetido no crea otro.
 * <p>
 * A propósito no lleva anotaciones de validación: un SOS nunca se rechaza. SosService normaliza lo que llega (una
 * coordenada fuera de rango o sin su pareja se descarta y la ubicación se completa con la última posición conocida;
 * los textos largos se recortan; una precisión negativa se ignora).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SosRequest {

    /** Opcional: sin GPS (túnel, montaña, equipo dañado) el SOS se registra igual. */
    private Double latitude;

    /** Opcional, junto con latitude. */
    private Double longitude;

    private Double accuracyMeters;

    private Long loadId;

    /** Solo de respaldo: si fleets-drivers conoce la placa del conductor, se usa la suya. */
    private String vehiclePlate;

    /** Solo de respaldo: si fleets-drivers conoce el nombre del conductor, se usa el suyo. */
    private String driverName;

    private String message;

    private UUID clientEventId;
}
