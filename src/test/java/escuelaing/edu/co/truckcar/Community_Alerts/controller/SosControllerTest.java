package escuelaing.edu.co.truckcar.Community_Alerts.controller;

import escuelaing.edu.co.truckcar.community_alerts.controller.SosController;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosRequest;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertUnauthenticatedException;
import escuelaing.edu.co.truckcar.community_alerts.exception.SosAccessDeniedException;
import escuelaing.edu.co.truckcar.community_alerts.model.SosAlert;
import escuelaing.edu.co.truckcar.community_alerts.model.SosStatus;
import escuelaing.edu.co.truckcar.community_alerts.service.SosService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SosControllerTest {

    private final SosService service = mock(SosService.class);
    private final SosController controller = new SosController(service);
    private final SosRequest request = SosRequest.builder().latitude(4.6).longitude(-74.1).build();

    private static SosAlert sos() {
        return SosAlert.builder().id(1L).driverId("driver-ana").latitude(4.6).longitude(-74.1)
                .status(SosStatus.ACTIVE).triggeredAt(Instant.now()).receivedAt(Instant.now()).build();
    }

    @Test
    void onlyAnAuthenticatedDriverCanTriggerSos() {
        assertThatThrownBy(() -> controller.trigger(request, null, null)).isInstanceOf(AlertUnauthenticatedException.class);
        assertThatThrownBy(() -> controller.trigger(request, "coord-1", "COORDINADOR")).isInstanceOf(SosAccessDeniedException.class);
        assertThatThrownBy(() -> controller.trigger(request, "gen-1", "GENERADOR")).isInstanceOf(SosAccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test
    void theDriverComesFromTheTokenAndANewSosIsAccepted() {
        when(service.trigger(eq("driver-ana"), eq(request), any())).thenReturn(new SosService.Result(sos(), true));

        var response = controller.trigger(request, "driver-ana", "CONDUCTOR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void aRetriedSosAnswersOkWithTheOriginal() {
        when(service.trigger(eq("driver-ana"), eq(request), any())).thenReturn(new SosService.Result(sos(), false));

        assertThat(controller.trigger(request, "driver-ana", "CONDUCTOR").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlyTheControlTowerListsAndAttendsSos() {
        assertThatThrownBy(() -> controller.active("driver-ana", "CONDUCTOR")).isInstanceOf(SosAccessDeniedException.class);
        assertThatThrownBy(() -> controller.acknowledge(1L, "gen-1", "GENERADOR")).isInstanceOf(SosAccessDeniedException.class);

        when(service.active()).thenReturn(List.of(sos()));
        assertThat(controller.active("coord-1", "COORDINADOR")).hasSize(1);
        when(service.acknowledge(1L, "admin-1")).thenReturn(sos());
        assertThat(controller.acknowledge(1L, "admin-1", "ADMIN").getId()).isEqualTo(1L);
        when(service.resolve(1L, "admin-1", "Atendido")).thenReturn(sos());
        assertThat(controller.resolve(1L, "Atendido", "admin-1", "ADMIN").getId()).isEqualTo(1L);
    }
}
