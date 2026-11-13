package escuelaing.edu.co.truckcar.community_alerts.exception;

/** El SOS ya fue resuelto por la torre: no se puede anular. Responde 409. */
public class SosNotCancellableException extends RuntimeException {

    public SosNotCancellableException(String message) {
        super(message);
    }
}
