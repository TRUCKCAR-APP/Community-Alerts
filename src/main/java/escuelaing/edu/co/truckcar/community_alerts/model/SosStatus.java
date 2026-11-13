package escuelaing.edu.co.truckcar.community_alerts.model;

/**
 * ACTIVE: la torre aún no la atiende. ACKNOWLEDGED: un coordinador la tomó. RESOLVED: emergencia atendida y cerrada.
 * CANCELLED: el propio conductor la anuló (falsa alarma) antes de que se resolviera.
 */
public enum SosStatus {
    ACTIVE,
    ACKNOWLEDGED,
    RESOLVED,
    CANCELLED
}
