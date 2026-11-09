package escuelaing.edu.co.truckcar.Community_Alerts.service;

import escuelaing.edu.co.truckcar.community_alerts.dto.request.CreateRoadAlertRequest;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertAlreadyConfirmedException;
import escuelaing.edu.co.truckcar.community_alerts.model.AlertSeverity;
import escuelaing.edu.co.truckcar.community_alerts.model.AlertType;
import escuelaing.edu.co.truckcar.community_alerts.model.RoadAlert;
import escuelaing.edu.co.truckcar.community_alerts.model.RoadAlertConfirmation;
import escuelaing.edu.co.truckcar.community_alerts.repository.RoadAlertConfirmationRepository;
import escuelaing.edu.co.truckcar.community_alerts.repository.RoadAlertRepository;
import escuelaing.edu.co.truckcar.community_alerts.service.OutboxEventService;
import escuelaing.edu.co.truckcar.community_alerts.service.RoadAlertService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoadAlertDeduplicationTest {

    @Mock
    private RoadAlertRepository repository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private RoadAlertConfirmationRepository confirmationRepository;

    @Test
    void associatesRecentNearbyAlertAndEmitsValidatedWhenThresholdIsReached() {
        RoadAlert existing = RoadAlert.builder()
                .id(10L)
                .clientAlertId("existing-alert")
                .alertType(AlertType.DERRUMBE)
                .confirmationsCount(2)
                .isActive(true)
                .build();
        when(repository.findRecentDuplicate(any(), any(Double.class), any(Double.class), any(), any(Double.class)))
                .thenReturn(Optional.of(existing));
        when(repository.save(any(RoadAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoadAlertService service = new RoadAlertService(repository, new GeometryFactory(), outboxEventService,
                confirmationRepository);
        CreateRoadAlertRequest request = CreateRoadAlertRequest.builder()
                .clientAlertId("new-alert")
                .driverId("driver-1")
                .alertType(AlertType.DERRUMBE)
                .severity(AlertSeverity.ALTA)
                .title("Derrumbe")
                .latitude(4.7)
                .longitude(-74.1)
                .build();

        assertThat(service.createAlert(request).getId()).isEqualTo(10L);

        assertThat(existing.getConfirmationsCount()).isEqualTo(3);
        verify(outboxEventService).enqueueAlertEvent(existing, "alert.confirmed", "CONFIRMED");
        verify(outboxEventService).enqueueAlertEvent(existing, "alert.validated", "VALIDATED");
        verify(repository).save(existing);
    }

    @Test
    void repeatedReportFromSameDriverDoesNotAddConfidence() {
        RoadAlert existing = RoadAlert.builder()
                .id(10L)
                .driverId("driver-1")
                .alertType(AlertType.DERRUMBE)
                .confirmationsCount(1)
                .isActive(true)
                .build();
        when(repository.findRecentDuplicate(any(), any(Double.class), any(Double.class), any(), any(Double.class)))
                .thenReturn(Optional.of(existing));
        when(confirmationRepository.existsByAlertIdAndUserId(10L, "driver-1")).thenReturn(true);

        RoadAlertService service = new RoadAlertService(repository, new GeometryFactory(), outboxEventService,
                confirmationRepository);
        service.createAlert(CreateRoadAlertRequest.builder()
                .driverId("driver-1")
                .alertType(AlertType.DERRUMBE)
                .severity(AlertSeverity.ALTA)
                .title("Derrumbe")
                .latitude(4.7)
                .longitude(-74.1)
                .build());

        assertThat(existing.getConfirmationsCount()).isEqualTo(1);
        verify(repository, never()).save(any(RoadAlert.class));
        verifyNoInteractions(outboxEventService);
    }

    @Test
    void confirmAlertCountsOneVotePerUser() {
        RoadAlert alert = RoadAlert.builder().id(10L).alertType(AlertType.DERRUMBE).confirmationsCount(1).build();
        when(repository.findById(10L)).thenReturn(Optional.of(alert));
        when(repository.save(any(RoadAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(confirmationRepository.existsByAlertIdAndUserId(10L, "driver-2")).thenReturn(false, true);

        RoadAlertService service = new RoadAlertService(repository, new GeometryFactory(), outboxEventService,
                confirmationRepository);

        assertThat(service.confirmAlert(10L, "driver-2").getConfirmationsCount()).isEqualTo(2);
        assertThatThrownBy(() -> service.confirmAlert(10L, "driver-2"))
                .isInstanceOf(AlertAlreadyConfirmedException.class);
        assertThat(alert.getConfirmationsCount()).isEqualTo(2);
        verify(confirmationRepository).save(any(RoadAlertConfirmation.class));
    }

    @Test
    void resolveAlertDeactivatesAndEmitsResolvedEvent() {
        RoadAlert alert = RoadAlert.builder().id(20L).alertType(AlertType.ACCIDENTE).isActive(true).build();
        when(repository.findById(20L)).thenReturn(Optional.of(alert));
        when(repository.save(any(RoadAlert.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoadAlertService service = new RoadAlertService(repository, new GeometryFactory(), outboxEventService,
                confirmationRepository);

        var response = service.resolveAlert(20L, "coord-1", "Vía despejada por grúa");

        assertThat(response.getIsActive()).isFalse();
        assertThat(response.getResolvedBy()).isEqualTo("coord-1");
        assertThat(response.getResolutionNotes()).isEqualTo("Vía despejada por grúa");
        assertThat(alert.getIsActive()).isFalse();
        verify(outboxEventService).enqueueAlertEvent(alert, "alert.resolved", "RESOLVED");
    }
}
