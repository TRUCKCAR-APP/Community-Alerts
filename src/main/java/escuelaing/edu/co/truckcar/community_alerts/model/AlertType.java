package escuelaing.edu.co.truckcar.community_alerts.model;

public enum AlertType {
    BASCULA,
    RETEN,
    DERRUMBE,
    GALIBO_REDUCIDO,
    PARADOR_SEGURO,
    ACCIDENTE,
    OBRA_EN_VIA,
    /** Congestión o trancón: no bloquea la vía, avisa demoras. Vence antes que las demás (ttl.trafico-hours). */
    TRAFICO_PESADO
}