package escuelaing.edu.co.truckcar.community_alerts.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.CreateRoadAlertRequest;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosRequest;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosRequestReader;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertAlreadyConfirmedException;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertNotFoundException;
import escuelaing.edu.co.truckcar.community_alerts.service.IRoadAlertService;
import escuelaing.edu.co.truckcar.community_alerts.service.SosService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Alertas y confirmaciones que el conductor registró sin señal (alert.reported, alert.confirmed), aplicadas en orden
 * cronológico. El reporte conserva la hora del dispositivo, así la deduplicación espacial y la ventana de validez de
 * la alerta usan el momento real del incidente y no el de la sincronización.
 */
@Slf4j
@Service
public class AlertSyncService {

    public static final String ALERT_REPORTED = "alert.reported";
    public static final String ALERT_CONFIRMED = "alert.confirmed";
    /** SOS pedido sin señal: se registra con la hora del dispositivo al reconectar. */
    public static final String ALERT_SOS = "alert.sos";

    private static final Comparator<SyncBatchRequest.Event> CHRONOLOGICAL = Comparator
            .comparing(SyncBatchRequest.Event::getOccurredAt)
            .thenComparing(event -> event.getSeq() == null ? 0L : event.getSeq());

    private final IngestionLedger ledger;
    private final IRoadAlertService alertService;
    private final SosService sosService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final TransactionTemplate transaction;
    private final TransactionTemplate separateTransaction;

    public AlertSyncService(IngestionLedger ledger, IRoadAlertService alertService, SosService sosService,
                            ObjectMapper objectMapper, Validator validator, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.alertService = alertService;
        this.sosService = sosService;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.separateTransaction = new TransactionTemplate(transactionManager);
        this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public SyncBatchResponse process(String driverId, SyncBatchRequest request) {
        SyncBatchResponse response = new SyncBatchResponse();
        request.getEvents().stream().sorted(CHRONOLOGICAL).forEach(event -> {
            try {
                Boolean fresh = transaction.execute(status -> {
                    if (!ledger.register(driverId, event, IngestionLedger.APPLIED, null)) {
                        return false;
                    }
                    apply(driverId, event);
                    return true;
                });
                (Boolean.TRUE.equals(fresh) ? response.getAccepted() : response.getDuplicates()).add(event.getClientEventId());
            } catch (AlertAlreadyConfirmedException | AlertNotFoundException | IllegalArgumentException rejection) {
                Boolean recorded = separateTransaction.execute(status ->
                        ledger.register(driverId, event, IngestionLedger.REJECTED, rejection.getMessage()));
                if (Boolean.TRUE.equals(recorded)) {
                    response.getRejected().add(new SyncBatchResponse.Rejected(event.getClientEventId(),
                            rejection.getMessage()));
                } else {
                    response.getDuplicates().add(event.getClientEventId());
                }
            }
        });
        log.info("Lote offline del conductor {}: {} aplicados, {} duplicados, {} rechazados", driverId,
                response.getAccepted().size(), response.getDuplicates().size(), response.getRejected().size());
        return response;
    }

    private void apply(String driverId, SyncBatchRequest.Event event) {
        switch (event.getKind()) {
            case ALERT_REPORTED -> {
                CreateRoadAlertRequest alert = alertOf(event.getPayload());
                // Autor = conductor autenticado; el id del evento es también la clave de la alerta en el dispositivo.
                alert.setDriverId(driverId);
                alert.setClientAlertId(event.getClientEventId().toString());
                alert.setReportedAt(LocalDateTime.ofInstant(event.getOccurredAt(), ZoneId.systemDefault()));
                alertService.createAlert(alert);
            }
            case ALERT_CONFIRMED -> {
                JsonNode alertId = event.getPayload().path("alertId");
                if (!alertId.canConvertToLong()) {
                    throw new IllegalArgumentException("La confirmación debe indicar alertId");
                }
                alertService.confirmAlert(alertId.asLong(), driverId);
            }
            case ALERT_SOS -> {
                // Un SOS de la cola offline nunca se rechaza por sus datos (igual que el POST en línea).
                SosRequest sos = SosRequestReader.read(event.getPayload());
                sos.setClientEventId(event.getClientEventId());
                sosService.trigger(driverId, sos, event.getOccurredAt());
            }
            default -> throw new IllegalArgumentException("Tipo de evento no admitido por Community-Alerts: "
                    + event.getKind());
        }
    }

    private <T> T validated(JsonNode payload, Class<T> type, String invalidMessage) {
        T value;
        try {
            value = objectMapper.treeToValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(invalidMessage + ": " + e.getOriginalMessage());
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream().map(ConstraintViolation::getMessage)
                    .sorted().collect(Collectors.joining("; ")));
        }
        return value;
    }

    private CreateRoadAlertRequest alertOf(JsonNode payload) {
        CreateRoadAlertRequest alert;
        try {
            alert = objectMapper.treeToValue(payload, CreateRoadAlertRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Alerta mal formada: " + e.getOriginalMessage());
        }
        Set<ConstraintViolation<CreateRoadAlertRequest>> violations = validator.validate(alert);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream().map(ConstraintViolation::getMessage)
                    .sorted().collect(Collectors.joining("; ")));
        }
        return alert;
    }
}
