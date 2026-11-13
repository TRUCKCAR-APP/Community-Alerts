package escuelaing.edu.co.truckcar.Community_Alerts.service;

import escuelaing.edu.co.truckcar.community_alerts.client.FleetsDriversClient;
import escuelaing.edu.co.truckcar.community_alerts.client.FleetsDriversClient.DriverSnapshot;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosRequest;
import escuelaing.edu.co.truckcar.community_alerts.exception.SosAccessDeniedException;
import escuelaing.edu.co.truckcar.community_alerts.exception.SosNotCancellableException;
import escuelaing.edu.co.truckcar.community_alerts.model.SosAlert;
import escuelaing.edu.co.truckcar.community_alerts.model.SosLocationSource;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private FleetsDriversClient fleetsDrivers;
    private SosService service;

    @BeforeEach
    void setUp() {
        repository = mock(SosAlertRepository.class);
        outbox = mock(OutboxEventService.class);
        publisher = mock(OutboxPublisher.class);
        fleetsDrivers = mock(FleetsDriversClient.class);
        when(fleetsDrivers.lastKnown(any())).thenReturn(Optional.empty());
        // Ejecutor síncrono: sin transacción activa, la publicación inmediata corre en el acto.
        service = new SosService(repository, outbox, publisher, Runnable::run, fleetsDrivers);
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

    private static SosRequest withoutLocation() {
        return SosRequest.builder().vehiclePlate("TDK-410").driverName("Ana").build();
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
        assertThat(sos.getLocationSource()).isEqualTo(SosLocationSource.DEVICE_GPS);
        assertThat(sos.getLatitude()).isEqualTo(4.61);
        assertThat(sos.getAccuracyMeters()).isEqualTo(12.0);
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
    void withoutGpsTheLastKnownPositionFromFleetsDriversIsUsed() {
        Instant seen = Instant.now().minus(4, ChronoUnit.MINUTES);
        when(fleetsDrivers.lastKnown("driver-ana"))
                .thenReturn(Optional.of(new DriverSnapshot(5.07, -75.52, seen, "Ana Gómez", "SKD-123", 44L)));

        SosAlert sos = service.trigger("driver-ana", withoutLocation(), Instant.now()).sos();

        assertThat(sos.getLocationSource()).isEqualTo(SosLocationSource.LAST_KNOWN);
        assertThat(sos.getLatitude()).isEqualTo(5.07);
        assertThat(sos.getLongitude()).isEqualTo(-75.52);
        assertThat(sos.getLocationRecordedAt()).isEqualTo(seen);
        assertThat(sos.getAccuracyMeters()).isNull();
        // El servidor manda: nombre, placa y carga de fleets-drivers, no los del cliente.
        assertThat(sos.getDriverName()).isEqualTo("Ana Gómez");
        assertThat(sos.getVehiclePlate()).isEqualTo("SKD-123");
        assertThat(sos.getLoadId()).isEqualTo(44L);
        verify(outbox).enqueueSosEvent(sos, SosService.SOS_TRIGGERED);
    }

    @Test
    void withoutGpsAndWithoutFleetsDriversTheSosIsStillRegisteredAsUnknown() {
        SosAlert sos = service.trigger("driver-ana", withoutLocation(), Instant.now()).sos();

        assertThat(sos.getStatus()).isEqualTo(SosStatus.ACTIVE);
        assertThat(sos.getLocationSource()).isEqualTo(SosLocationSource.UNKNOWN);
        assertThat(sos.getLatitude()).isNull();
        assertThat(sos.getLongitude()).isNull();
        assertThat(sos.getDriverName()).isEqualTo("Ana");
        verify(outbox).enqueueSosEvent(sos, SosService.SOS_TRIGGERED);
    }

    @Test
    void aStateWithoutPositionInFleetsDriversGivesUnknownButKeepsTheServerIdentity() {
        when(fleetsDrivers.lastKnown("driver-ana"))
                .thenReturn(Optional.of(new DriverSnapshot(null, null, null, "Ana Gómez", "SKD-123", null)));

        SosAlert sos = service.trigger("driver-ana", withoutLocation(), Instant.now()).sos();

        assertThat(sos.getLocationSource()).isEqualTo(SosLocationSource.UNKNOWN);
        assertThat(sos.getVehiclePlate()).isEqualTo("SKD-123");
    }

    @Test
    void invalidDeviceCoordinatesAreDiscardedInsteadOfRejectingTheSos() {
        when(fleetsDrivers.lastKnown("driver-ana"))
                .thenReturn(Optional.of(new DriverSnapshot(5.07, -75.52, null, null, null, null)));

        SosAlert outOfRange = service.trigger("driver-ana",
                SosRequest.builder().latitude(123.0).longitude(-74.0).build(), Instant.now()).sos();
        SosAlert onlyOne = service.trigger("driver-ana",
                SosRequest.builder().latitude(4.6).build(), Instant.now()).sos();
        SosAlert nullIsland = service.trigger("driver-ana",
                SosRequest.builder().latitude(0.0).longitude(0.0).build(), Instant.now()).sos();

        assertThat(List.of(outOfRange, onlyOne, nullIsland))
                .allSatisfy(sos -> {
                    assertThat(sos.getLocationSource()).isEqualTo(SosLocationSource.LAST_KNOWN);
                    assertThat(sos.getLatitude()).isEqualTo(5.07);
                });
    }

    @Test
    void longTextsAreTruncatedAndANegativeAccuracyIsIgnored() {
        SosRequest noisy = SosRequest.builder().latitude(4.6).longitude(-74.1).accuracyMeters(-5.0)
                .message("x".repeat(900)).driverName("n".repeat(400)).vehiclePlate("p".repeat(60)).build();

        SosAlert sos = service.trigger("driver-ana", noisy, Instant.now()).sos();

        assertThat(sos.getMessage()).hasSize(500);
        assertThat(sos.getDriverName()).hasSize(150);
        assertThat(sos.getVehiclePlate()).hasSize(20);
        assertThat(sos.getAccuracyMeters()).isNull();
        assertThat(sos.getLocationSource()).isEqualTo(SosLocationSource.DEVICE_GPS);
    }

    @Test
    void theDriverCancelsTheirOwnSosOnceAndTheEventIsEmitted() {
        SosAlert active = SosAlert.builder().id(21L).status(SosStatus.ACTIVE).driverId("driver-ana").build();
        when(repository.findById(21L)).thenReturn(Optional.of(active));

        SosAlert first = service.cancel(21L, "driver-ana", "  Lo activé sin querer  ");
        SosAlert second = service.cancel(21L, "driver-ana", "otra vez");

        assertThat(first.getStatus()).isEqualTo(SosStatus.CANCELLED);
        assertThat(first.getCancelledAt()).isNotNull();
        assertThat(first.getCancellationReason()).isEqualTo("Lo activé sin querer");
        assertThat(second.getCancellationReason()).isEqualTo("Lo activé sin querer");
        verify(outbox, times(1)).enqueueSosEvent(active, SosService.SOS_CANCELLED);
    }

    @Test
    void anAcknowledgedSosCanStillBeCancelled() {
        SosAlert acked = SosAlert.builder().id(22L).status(SosStatus.ACKNOWLEDGED).driverId("driver-ana").build();
        when(repository.findById(22L)).thenReturn(Optional.of(acked));

        assertThat(service.cancel(22L, "driver-ana", null).getStatus()).isEqualTo(SosStatus.CANCELLED);
    }

    @Test
    void anotherDriverCannotCancelItAndAResolvedSosCannotBeCancelled() {
        SosAlert active = SosAlert.builder().id(23L).status(SosStatus.ACTIVE).driverId("driver-ana").build();
        SosAlert resolved = SosAlert.builder().id(24L).status(SosStatus.RESOLVED).driverId("driver-ana").build();
        when(repository.findById(23L)).thenReturn(Optional.of(active));
        when(repository.findById(24L)).thenReturn(Optional.of(resolved));

        assertThatThrownBy(() -> service.cancel(23L, "driver-luis", null)).isInstanceOf(SosAccessDeniedException.class);
        assertThatThrownBy(() -> service.cancel(24L, "driver-ana", null)).isInstanceOf(SosNotCancellableException.class);
        verify(outbox, never()).enqueueSosEvent(any(), any());
    }

    @Test
    void listingWithoutStatusesReturnsTheOpenEmergencies() {
        service.list(null);
        service.list(Set.of(SosStatus.ACTIVE));

        verify(repository).findByStatusInOrderByTriggeredAtDesc(SosService.OPEN_STATUSES);
        verify(repository).findByStatusInOrderByTriggeredAtDesc(Set.of(SosStatus.ACTIVE));
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
