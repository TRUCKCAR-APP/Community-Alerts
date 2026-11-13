package escuelaing.edu.co.truckcar.community_alerts.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Cuerpo opcional de PATCH /alerts/sos/{id}/cancel: por qué el conductor anula el SOS (falsa alarma). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SosCancelRequest {

    /** Opcional; se recorta a 500 caracteres. */
    private String reason;
}
