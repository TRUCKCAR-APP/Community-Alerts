package escuelaing.edu.co.truckcar.community_alerts.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AlertNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(AlertNotFoundException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.NOT_FOUND.value());
        error.put("error", "Alerta no encontrada");
        error.put("message", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(AlertAlreadyConfirmedException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyConfirmed(AlertAlreadyConfirmedException ex) {
        return simpleError(HttpStatus.CONFLICT, "Alerta ya confirmada", ex.getMessage());
    }

    @ExceptionHandler(SosAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleSosAccessDenied(SosAccessDeniedException ex) {
        return simpleError(HttpStatus.FORBIDDEN, "Acceso denegado", ex.getMessage());
    }

    @ExceptionHandler(AlertUnauthenticatedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthenticated(AlertUnauthenticatedException ex) {
        return simpleError(HttpStatus.UNAUTHORIZED, "Usuario no autenticado", ex.getMessage());
    }

    /** Restricciones sobre parámetros (@Max en radiusMeters): validación del método en Spring 6.1. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleParameterValidation(HandlerMethodValidationException ex) {
        String detail = ex.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> result.getMethodParameter().getParameterName() + ": " + error.getDefaultMessage()))
                .collect(Collectors.joining("; "));
        return simpleError(HttpStatus.BAD_REQUEST, "Validación fallida", detail);
    }

    /** JSON mal formado, UTF-8 inválido o un valor que no corresponde al tipo (enum desconocido): error del cliente. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Cuerpo ilegible: {}", ex.getMessage());
        // Sin el detalle del parser en la respuesta: puede incluir fragmentos del cuerpo y nombres de clases internas.
        return simpleError(HttpStatus.BAD_REQUEST, "Cuerpo de la solicitud inválido",
                "El cuerpo debe ser JSON válido en UTF-8 con los campos y valores esperados");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return simpleError(HttpStatus.BAD_REQUEST, "Parámetro inválido",
                ex.getName() + ": valor con un tipo incorrecto");
    }

    private static ResponseEntity<Map<String, Object>> simpleError(HttpStatusCode status, String error, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        return new ResponseEntity<>(body, status);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, Object> error = new HashMap<>();
        Map<String, String> fieldErrors = new HashMap<>();

        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }

        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.BAD_REQUEST.value());
        error.put("error", "Validación fallida");
        error.put("details", fieldErrors);
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        // Excepciones de Spring MVC con su propio estado (405, 415, parámetro faltante...): este handler genérico las
        // capturaría antes que el resolver por defecto y las convertiría en 500.
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            return simpleError(errorResponse.getStatusCode(), "Solicitud inválida", errorResponse.getBody().getDetail());
        }
        log.error("Error no controlado en ms-community-alerts: ", ex);
        Map<String, Object> error = new HashMap<>();
        error.put("timestamp", LocalDateTime.now());
        error.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        error.put("error", "Error interno");
        error.put("message", "No se pudo procesar la solicitud de las alertas viales");
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}