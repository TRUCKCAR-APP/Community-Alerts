package escuelaing.edu.co.truckcar.community_alerts.exception;

/** User-Identity no respondió: sin saber qué conductores son de la empresa no se muestran sus SOS (503). */
public class CompanyLookupUnavailableException extends RuntimeException {
    public CompanyLookupUnavailableException(String message) {
        super(message);
    }
}
