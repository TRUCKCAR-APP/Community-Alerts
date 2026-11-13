package escuelaing.edu.co.truckcar.community_alerts.service;

import escuelaing.edu.co.truckcar.community_alerts.client.FleetsDriversClient;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosRequest;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertNotFoundException;
import escuelaing.edu.co.truckcar.community_alerts.exception.SosAccessDeniedException;
import escuelaing.edu.co.truckcar.community_alerts.exception.SosNotCancellableException;
import escuelaing.edu.co.truckcar.community_alerts.model.SosAlert;
import escuelaing.edu.co.truckcar.community_alerts.model.SosLocationSource;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Botón SOS: no pasa por la validación de la comunidad. El SOS se guarda y su evento alert.sos.triggered entra al
 * outbox en la misma transacción (no se pierde si Kafka falla) y, al confirmarse la transacción, se dispara la
 * publicación sin esperar al ciclo programado del outbox.
 * <p>
 * Un SOS nunca se rechaza por sus datos: sin ubicación válida del dispositivo se usa la última posición conocida del
 * conductor en fleets-drivers (LAST_KNOWN) y, si tampoco hay, queda como UNKNOWN. El nombre y la placa se toman de
 * fleets-drivers cuando los conoce; los del cliente solo son respaldo.
 */
@Slf4j
@Service
public class SosService {

    public static final String SOS_TRIGGERED = "alert.sos.triggered";
    public static final String SOS_ACKNOWLEDGED = "alert.sos.acknowledged";
    public static final String SOS_RESOLVED = "alert.sos.resolved";
    public static final String SOS_CANCELLED = "alert.sos.cancelled";

    /** Lo que lista la torre si no pide estados: las emergencias abiertas (atendidas o no). */
    public static final Set<SosStatus> OPEN_STATUSES = Set.of(SosStatus.ACTIVE, SosStatus.ACKNOWLEDGED);

    private static final int MAX_TEXT = 500;
    private static final int MAX_NAME = 150;
    private static final int MAX_PLATE = 20;

    private final SosAlertRepository repository;
    private final OutboxEventService outbox;
    private final OutboxPublisher publisher;
    private final TaskExecutor executor;
    private final FleetsDriversClient fleetsDrivers;

    public SosService(SosAlertRepository repository, OutboxEventService outbox, OutboxPublisher publisher,
                      @Qualifier("applicationTaskExecutor") TaskExecutor executor, FleetsDriversClient fleetsDrivers) {
        this.repository = repository;
        this.outbox = outbox;
        this.publisher = publisher;
        this.executor = executor;
        this.fleetsDrivers = fleetsDrivers;
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
        // fleets-drivers responde en 1,5 s como máximo o se sigue sin él: el SOS no depende de otro servicio.
        Optional<FleetsDriversClient.DriverSnapshot> known = fleetsDrivers.lastKnown(driverId);
        boolean deviceLocation = validCoordinates(request.getLatitude(), request.getLongitude());
        if (!deviceLocation && (request.getLatitude() != null || request.getLongitude() != null)) {
            log.warn("SOS del conductor {} con ubicación inválida ({}, {}): se descarta y se completa en el servidor",
                    driverId, request.getLatitude(), request.getLongitude());
        }

        SosLocationSource source;
        Double latitude = null;
        Double longitude = null;
        Double accuracy = null;
        Instant recordedAt = null;
        if (deviceLocation) {
            source = SosLocationSource.DEVICE_GPS;
            latitude = request.getLatitude();
            longitude = request.getLongitude();
            Double reported = request.getAccuracyMeters();
            accuracy = reported != null && Double.isFinite(reported) && reported >= 0 ? reported : null;
        } else if (known.isPresent() && known.get().hasPosition()) {
            source = SosLocationSource.LAST_KNOWN;
            latitude = known.get().latitude();
            longitude = known.get().longitude();
            recordedAt = known.get().recordedAt();
        } else {
            source = SosLocationSource.UNKNOWN;
        }

        String serverName = known.map(FleetsDriversClient.DriverSnapshot::driverName).orElse(null);
        String serverPlate = known.map(FleetsDriversClient.DriverSnapshot::vehiclePlate).orElse(null);
        Long serverLoad = known.map(FleetsDriversClient.DriverSnapshot::currentLoadId).orElse(null);

        SosAlert sos = repository.save(SosAlert.builder()
                .clientEventId(request.getClientEventId())
                .driverId(driverId)
                .driverName(limit(serverName != null ? serverName : request.getDriverName(), MAX_NAME))
                .vehiclePlate(limit(serverPlate != null ? serverPlate : request.getVehiclePlate(), MAX_PLATE))
                .loadId(request.getLoadId() != null ? request.getLoadId() : serverLoad)
                .latitude(latitude)
                .longitude(longitude)
                .accuracyMeters(accuracy)
                .locationSource(source)
                .locationRecordedAt(recordedAt)
                .message(limit(request.getMessage(), MAX_TEXT))
                .status(SosStatus.ACTIVE)
                // Hora del dispositivo (SOS guardado sin señal), nunca en el futuro.
                .triggeredAt(triggeredAt == null || triggeredAt.isAfter(now) ? now : triggeredAt)
                .receivedAt(now)
                .build());
        outbox.enqueueSosEvent(sos, SOS_TRIGGERED);
        publishAfterCommit();
        Metrics.counter("truckcar.sos.triggered").increment();
        Metrics.counter("truckcar.sos.location", "source", source.name()).increment();
        log.warn("SOS #{} del conductor {} en ({}, {}) fuente={} carga={}", sos.getId(), driverId, sos.getLatitude(),
                sos.getLongitude(), source, sos.getLoadId());
        return new Result(sos, true);
    }

    /** Emergencias abiertas (ACTIVE y ACKNOWLEDGED), lo que la torre ve por defecto. */
    @Transactional(readOnly = true)
    public List<SosAlert> active() {
        return list(OPEN_STATUSES);
    }

    /** SOS en los estados pedidos; sin estados, las emergencias abiertas. */
    @Transactional(readOnly = true)
    public List<SosAlert> list(Collection<SosStatus> statuses) {
        Collection<SosStatus> wanted = statuses == null || statuses.isEmpty() ? OPEN_STATUSES : statuses;
        return repository.findByStatusInOrderByTriggeredAtDesc(wanted);
    }

    /**
     * El conductor anula su propio SOS (falsa alarma). Idempotente: anular uno ya anulado devuelve su estado sin
     * volver a emitir el evento. Uno ya resuelto por la torre no se puede anular (409).
     */
    @Transactional
    public SosAlert cancel(Long sosId, String driverId, String reason) {
        SosAlert sos = repository.findById(sosId)
                .orElseThrow(() -> new AlertNotFoundException("No existe el SOS #" + sosId));
        if (!sos.getDriverId().equals(driverId)) {
            throw new SosAccessDeniedException("Un conductor solo puede anular su propio SOS");
        }
        if (sos.getStatus() == SosStatus.CANCELLED) {
            return sos;
        }
        if (sos.getStatus() == SosStatus.RESOLVED) {
            throw new SosNotCancellableException("El SOS #" + sosId + " ya fue resuelto por la torre de control");
        }
        sos.setStatus(SosStatus.CANCELLED);
        sos.setCancelledAt(Instant.now());
        sos.setCancellationReason(limit(reason, MAX_TEXT));
        SosAlert saved = repository.save(sos);
        outbox.enqueueSosEvent(saved, SOS_CANCELLED);
        publishAfterCommit();
        Metrics.counter("truckcar.sos.cancelled").increment();
        log.info("SOS #{} anulado por su conductor {}", sosId, driverId);
        return saved;
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

    /** Recorta en vez de rechazar: un SOS con un mensaje largo se registra igual. */
    private static String limit(String value, int max) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static boolean validCoordinates(Double latitude, Double longitude) {
        return latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180
                // (0, 0) es lo que algunos dispositivos envían sin señal: un punto en el océano Atlántico.
                && !(latitude == 0.0 && longitude == 0.0);
    }
}
