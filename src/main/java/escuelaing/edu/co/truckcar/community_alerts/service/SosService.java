package escuelaing.edu.co.truckcar.community_alerts.service;

import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosRequest;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertNotFoundException;
import escuelaing.edu.co.truckcar.community_alerts.model.SosAlert;
import escuelaing.edu.co.truckcar.community_alerts.model.SosStatus;
import escuelaing.edu.co.truckcar.community_alerts.repository.SosAlertRepository;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;

/**
 * Botón SOS: no pasa por la validación de la comunidad. El SOS se guarda y su evento alert.sos.triggered entra al
 * outbox en la misma transacción (no se pierde si Kafka falla) y, al confirmarse la transacción, se dispara la
 * publicación sin esperar al ciclo programado del outbox.
 */
@Slf4j
@Service
public class SosService {

    public static final String SOS_TRIGGERED = "alert.sos.triggered";
    public static final String SOS_ACKNOWLEDGED = "alert.sos.acknowledged";
    public static final String SOS_RESOLVED = "alert.sos.resolved";

    private final SosAlertRepository repository;
    private final OutboxEventService outbox;
    private final OutboxPublisher publisher;
    private final TaskExecutor executor;

    public SosService(SosAlertRepository repository, OutboxEventService outbox, OutboxPublisher publisher,
                      @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.repository = repository;
        this.outbox = outbox;
        this.publisher = publisher;
        this.executor = executor;
    }

    /** created=false: el mismo clientEventId ya estaba registrado (reintento) y se devuelve el SOS existente. */
    public record Result(SosAlert sos, boolean created) {
    }

    @Transactional
    public Result trigger(String driverId, SosRequest request, Instant triggeredAt) {
        if (request.getClientEventId() != null) {
            var existing = repository.findByClientEventId(request.getClientEventId());
            if (existing.isPresent()) {
                return new Result(existing.get(), false);
            }
        }
        Instant now = Instant.now();
        SosAlert sos = repository.save(SosAlert.builder()
                .clientEventId(request.getClientEventId())
                .driverId(driverId)
                .driverName(trim(request.getDriverName()))
                .vehiclePlate(trim(request.getVehiclePlate()))
                .loadId(request.getLoadId())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .accuracyMeters(request.getAccuracyMeters())
                .message(trim(request.getMessage()))
                .status(SosStatus.ACTIVE)
                // Hora del dispositivo (SOS guardado sin señal), nunca en el futuro.
                .triggeredAt(triggeredAt == null || triggeredAt.isAfter(now) ? now : triggeredAt)
                .receivedAt(now)
                .build());
        outbox.enqueueSosEvent(sos, SOS_TRIGGERED);
        publishAfterCommit();
        Metrics.counter("truckcar.sos.triggered").increment();
        log.warn("SOS #{} del conductor {} en ({}, {}) carga={}", sos.getId(), driverId, sos.getLatitude(),
                sos.getLongitude(), sos.getLoadId());
        return new Result(sos, true);
    }

    @Transactional(readOnly = true)
    public List<SosAlert> active() {
        return repository.findByStatusInOrderByTriggeredAtDesc(List.of(SosStatus.ACTIVE, SosStatus.ACKNOWLEDGED));
    }

    /** Idempotente: atender un SOS ya atendido devuelve su estado sin volver a emitir el evento. */
    @Transactional
    public SosAlert acknowledge(Long sosId, String coordinatorId) {
        SosAlert sos = repository.findById(sosId)
                .orElseThrow(() -> new AlertNotFoundException("No existe el SOS #" + sosId));
        if (sos.getStatus() == SosStatus.ACKNOWLEDGED) {
            return sos;
        }
        sos.setStatus(SosStatus.ACKNOWLEDGED);
        sos.setAcknowledgedBy(coordinatorId);
        sos.setAcknowledgedAt(Instant.now());
        SosAlert saved = repository.save(sos);
        outbox.enqueueSosEvent(saved, SOS_ACKNOWLEDGED);
        publishAfterCommit();
        return saved;
    }

    /** Idempotente: resolver un SOS ya resuelto devuelve su estado sin volver a emitir el evento. */
    @Transactional
    public SosAlert resolve(Long sosId, String coordinatorId, String notes) {
        SosAlert sos = repository.findById(sosId)
                .orElseThrow(() -> new AlertNotFoundException("No existe el SOS #" + sosId));
        if (sos.getStatus() == SosStatus.RESOLVED) {
            return sos;
        }
        sos.setStatus(SosStatus.RESOLVED);
        sos.setResolvedBy(coordinatorId);
        sos.setResolvedAt(Instant.now());
        if (notes != null && !notes.isBlank()) {
            sos.setResolutionNotes(notes.trim());
        }
        SosAlert saved = repository.save(sos);
        outbox.enqueueSosEvent(saved, SOS_RESOLVED);
        publishAfterCommit();
        log.info("SOS #{} resuelto por coordinador {}", sosId, coordinatorId);
        return saved;
    }

    /** En otro hilo y después del commit: la publicación abre su propia transacción con el evento ya guardado. */
    private void publishAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            executor.execute(publisher::publishPendingEvents);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(publisher::publishPendingEvents);
            }
        });
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
