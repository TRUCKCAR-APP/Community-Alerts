package escuelaing.edu.co.truckcar.Community_Alerts.service;

import escuelaing.edu.co.truckcar.community_alerts.model.AlertType;
import escuelaing.edu.co.truckcar.community_alerts.model.RoadAlert;
import escuelaing.edu.co.truckcar.community_alerts.repository.RoadAlertConfirmationRepository;
import escuelaing.edu.co.truckcar.community_alerts.repository.RoadAlertRepository;
import escuelaing.edu.co.truckcar.community_alerts.service.OutboxEventService;
import escuelaing.edu.co.truckcar.community_alerts.service.RoadAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Una alerta vencida se desactiva en la base y se anuncia una sola vez como alert.expired. */
@ExtendWith(MockitoExtension.class)
class AlertExpiryTest {

    @Mock
    private RoadAlertRepository repository;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private RoadAlertConfirmationRepository confirmationRepository;

    private RoadAlertService service;

    @BeforeEach
    void setUp() {
        service = new RoadAlertService(repository, new GeometryFactory(), outboxEventService, confirmationRepository);
        ReflectionTestUtils.setField(service, "defaultTtlHours", 24L);
        ReflectionTestUtils.setField(service, "derrumbeTtlHours", 6L);
    }

    private static RoadAlert alert(long id, AlertType type, int hoursAgo) {
        return RoadAlert.builder().id(id).alertType(type).isActive(true)
                .reportedAt(LocalDateTime.now().minusHours(hoursAgo)).build();
    }

    @Test
    void expiresEachAlertAfterItsOwnTtl() {
        RoadAlert oldLandslide = alert(1, AlertType.DERRUMBE, 7);   // TTL 6 h: vencida
        RoadAlert recentWork = alert(2, AlertType.OBRA_EN_VIA, 7);  // TTL 24 h: sigue activa
        RoadAlert oldAccident = alert(3, AlertType.ACCIDENTE, 25);  // TTL 24 h: vencida
        when(repository.findByIsActiveTrueAndReportedAtBefore(any()))
                .thenReturn(List.of(oldLandslide, recentWork, oldAccident));
        when(repository.deactivateIfActive(1L)).thenReturn(1);
        when(repository.deactivateIfActive(3L)).thenReturn(1);

        assertThat(service.expireAlerts()).isEqualTo(2);

        verify(outboxEventService).enqueueAlertEvent(oldLandslide, "alert.expired", "EXPIRED");
        verify(outboxEventService).enqueueAlertEvent(oldAccident, "alert.expired", "EXPIRED");
        verify(repository, never()).deactivateIfActive(2L);
        assertThat(oldLandslide.getIsActive()).isFalse();
        assertThat(recentWork.getIsActive()).isTrue();
    }

    @Test
    void onlyLooksAtAlertsOlderThanTheShortestTtl() {
        when(repository.findByIsActiveTrueAndReportedAtBefore(any())).thenReturn(List.of());

        service.expireAlerts();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findByIsActiveTrueAndReportedAtBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(LocalDateTime.now().minusHours(6).minusMinutes(1),
                LocalDateTime.now().minusHours(6).plusMinutes(1));
    }

    @Test
    void anotherReplicaAlreadyExpiredItSoNoSecondEvent() {
        when(repository.findByIsActiveTrueAndReportedAtBefore(any()))
                .thenReturn(List.of(alert(1, AlertType.DERRUMBE, 7)));
        when(repository.deactivateIfActive(1L)).thenReturn(0);

        assertThat(service.expireAlerts()).isZero();

        verify(outboxEventService, never()).enqueueAlertEvent(any(), anyString(), anyString());
    }
}
