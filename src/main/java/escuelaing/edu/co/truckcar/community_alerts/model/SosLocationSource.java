package escuelaing.edu.co.truckcar.community_alerts.model;

/**
 * De dónde salió la ubicación de un SOS. Un SOS nunca se rechaza por no traer ubicación: se completa con la última
 * posición conocida del conductor o queda como desconocida, y la torre ve cuál de los tres casos es.
 * <ul>
 *   <li>DEVICE_GPS: la envió el dispositivo con el SOS.</li>
 *   <li>LAST_KNOWN: el dispositivo no la tenía; es la última posición del conductor en fleets-drivers (ver
 *       locationRecordedAt para saber qué tan vieja es).</li>
 *   <li>UNKNOWN: no hay ninguna posición; la torre debe contactar al conductor para ubicarlo.</li>
 * </ul>
 */
public enum SosLocationSource {
    DEVICE_GPS,
    LAST_KNOWN,
    UNKNOWN
}
