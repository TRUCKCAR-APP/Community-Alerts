package escuelaing.edu.co.truckcar.Community_Alerts.service;

import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosRequest;
import escuelaing.edu.co.truckcar.community_alerts.model.SosAlert;
import escuelaing.edu.co.truckcar.community_alerts.model.SosStatus;
import escuelaing.edu.co.truckcar.community_alerts.repository.SosAlertRepository;
import escuelaing.edu.co.truckcar.community_alerts.service.OutboxEventService;
import escuelaing.edu.co.truckcar.community_alerts.service.OutboxPublisher;
import escuelaing.edu.co.truckcar.community_alerts.service.SosService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SosServiceTest {

    private SosAlertRepository repository;
    private OutboxEventService outbox;
    private OutboxPublisher publisher;
    private SosService service;

    @BeforeEach
    void setUp() {
        repository = mock(SosAlertRepository.class);
        outbox = mock(OutboxEventService.class);
        publisher = mock(OutboxPublisher.class);
        // Ejecutor síncrono: sin transacción activa, la publicación inmediata corre en el acto.
        service = new SosService(repository, outbox, publisher, Runnable::run);
        when(repository.save(any(SosAlert.class))).thenAnswer(invocation -> {
            SosAlert sos = invocation.getArgument(0);
            if (sos.getId() == null) sos.setId(7L);
            return sos;
        });
    }

    private static SosRequest request(UUID clientEventId) {
        return SosRequest.builder().latitude(4.61).longitude(-74.08).accuracyMeters(12.0).loadId(30L)
                .vehiclePlate(" TDK-410 ").driverName("Ana").message("  ").clientEventId(clientEventId).build();
    }

    @Test
    void triggerStoresAnActiveSosQueuesTheEventAndPublishesImmediately() {
        Instant pressedAt = Instant.now().minus(3, ChronoUnit.MINUTES);

        SosService.Result result = service.trigger("driver-ana", request(UUID.randomUUID()), pressedAt);

        assertThat(result.created()).isTrue();
        SosAlert sos = result.sos();
        assertThat(sos.getDriverId()).isEqualTo("driver-ana");
        assertThat(sos.getStatus()).isEqualTo(SosStatus.ACTIVE);
        assertThat(sos.getVehiclePlate()).isEqualTo("TDK-410");
        assertThat(sos.getMessage()).isNull();
        assertThat(sos.getTriggeredAt()).isEqualTo(pressedAt);
        verify(outbox).enqueueSosEvent(sos, SosService.SOS_TRIGGERED);
        verify(publisher).publishPendingEvents();
    }

    @Test
    void retryingWithTheSameClientEventIdReturnsTheExistingSos() {
        UUID clientEventId = UUID.randomUUID();
        SosAlert existing = SosAlert.builder().id(3L).clientEventId(clientEventId).status(SosStatus.ACTIVE).build();
        when(repository.findByClientEventId(clientEventId)).thenReturn(Optional.of(existing));

        SosService.Result result = service.trigger("driver-ana", request(clientEventId), Instant.now());

        assertThat(result.created()).isFalse();
        assertThat(result.sos()).isSameAs(existing);
        verify(repository, never()).save(any());
        verify(outbox, never()).enqueueSosEvent(any(), any());
    }

    @Test
    void aDeviceClockInTheFutureIsNotTrusted() {
        Instant future = Instant.now().plus(2, ChronoUnit.HOURS);

        SosAlert sos = service.trigger("driver-ana", request(null), future).sos();

        assertThat(sos.getTriggeredAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void acknowledgingIsIdempotentAndRecordsWhoAttended() {
        SosAlert active = SosAlert.builder().id(9L).status(SosStatus.ACTIVE).driverId("driver-ana").build();
        when(repository.findById(9L)).thenReturn(Optional.of(active));

        SosAlert first = service.acknowledge(9L, "coord-1");
        SosAlert second = service.acknowledge(9L, "coord-2");

        assertThat(first.getStatus()).isEqualTo(SosStatus.ACKNOWLEDGED);
        assertThat(second.getAcknowledgedBy()).isEqualTo("coord-1");
        ArgumentCaptor<String> events = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueueSosEvent(any(), events.capture());
        assertThat(events.getValue()).isEqualTo(SosService.SOS_ACKNOWLEDGED);
    }

    @Test
    void resolvingIsIdempotentAndRecordsWhoResolved() {
        SosAlert acked = SosAlert.builder().id(15L).status(SosStatus.ACKNOWLEDGED).driverId("driver-ana").build();
        when(repository.findById(15L)).thenReturn(Optional.of(acked));

        SosAlert first = service.resolve(15L, "coord-1", "Ambulancia despachada y conductor a salvo");
        SosAlert second = service.resolve(15L, "coord-2", "Ignorado");

        assertThat(first.getStatus()).isEqualTo(SosStatus.RESOLVED);
        assertThat(first.getResolvedBy()).isEqualTo("coord-1");
        assertThat(first.getResolutionNotes()).isEqualTo("Ambulancia despachada y conductor a salvo");
        assertThat(second.getResolvedBy()).isEqualTo("coord-1");
        ArgumentCaptor<String> events = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).enqueueSosEvent(any(), events.capture());
        assertThat(events.getValue()).isEqualTo(SosService.SOS_RESOLVED);
    }
}
