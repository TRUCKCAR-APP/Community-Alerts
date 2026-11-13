package escuelaing.edu.co.truckcar.Community_Alerts.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import escuelaing.edu.co.truckcar.community_alerts.controller.SosController;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosCancelRequest;
import escuelaing.edu.co.truckcar.community_alerts.dto.request.SosRequest;
import escuelaing.edu.co.truckcar.community_alerts.exception.AlertUnauthenticatedException;
import escuelaing.edu.co.truckcar.community_alerts.exception.SosAccessDeniedException;
import escuelaing.edu.co.truckcar.community_alerts.model.SosAlert;
import escuelaing.edu.co.truckcar.community_alerts.model.SosLocationSource;
import escuelaing.edu.co.truckcar.community_alerts.model.SosStatus;
import escuelaing.edu.co.truckcar.community_alerts.service.SosService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SosControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SosService service = mock(SosService.class);
    private final SosController controller = new SosController(service);
    private final JsonNode body = mapper.createObjectNode().put("latitude", 4.6).put("longitude", -74.1);

    private static SosAlert sos() {
        return SosAlert.builder().id(1L).driverId("driver-ana").latitude(4.6).longitude(-74.1)
                .locationSource(SosLocationSource.DEVICE_GPS)
                .status(SosStatus.ACTIVE).triggeredAt(Instant.now()).receivedAt(Instant.now()).build();
    }

    @Test
    void onlyAnAuthenticatedDriverCanTriggerSos() {
        assertThatThrownBy(() -> controller.trigger(body, null, null)).isInstanceOf(AlertUnauthenticatedException.class);
        assertThatThrownBy(() -> controller.trigger(body, "coord-1", "COORDINADOR")).isInstanceOf(SosAccessDeniedException.class);
        assertThatThrownBy(() -> controller.trigger(body, "gen-1", "GENERADOR")).isInstanceOf(SosAccessDeniedException.class);
        verifyNoInteractions(service);
    }

    @Test
    void theDriverComesFromTheTokenAndANewSosIsAccepted() {
        when(service.trigger(eq("driver-ana"), any(), any())).thenReturn(new SosService.Result(sos(), true));

        var response = controller.trigger(body, "driver-ana", "CONDUCTOR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().getStatus()).isEqualTo("ACTIVE");
        assertThat(response.getBody().getLocationSource()).isEqualTo("DEVICE_GPS");
    }

    @Test
    void aBodyWithMissingOrMalformedLocationStillReachesTheServiceInsteadOf400() throws Exception {
        when(service.trigger(eq("driver-ana"), any(), any())).thenReturn(new SosService.Result(sos(), true));
        JsonNode malformed = mapper.readTree("{\"latitude\":\"abc\",\"loadId\":\"x\",\"message\":\"Ayuda\"}");

        var response = controller.trigger(malformed, "driver-ana", "CONDUCTOR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ArgumentCaptor<SosRequest> sent = ArgumentCaptor.forClass(SosRequest.class);
        verify(service).trigger(eq("driver-ana"), sent.capture(), any());
        assertThat(sent.getValue().getLatitude()).isNull();
        assertThat(sent.getValue().getLoadId()).isNull();
        assertThat(sent.getValue().getMessage()).isEqualTo("Ayuda");
    }

    @Test
    void aRetriedSosAnswersOkWithTheOriginal() {
        when(service.trigger(eq("driver-ana"), any(), any())).thenReturn(new SosService.Result(sos(), false));

        assertThat(controller.trigger(body, "driver-ana", "CONDUCTOR").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlyTheControlTowerListsAndAttendsSos() {
        assertThatThrownBy(() -> controller.active(null, "driver-ana", "CONDUCTOR")).isInstanceOf(SosAccessDeniedException.class);
        assertThatThrownBy(() -> controller.acknowledge(1L, "gen-1", "GENERADOR")).isInstanceOf(SosAccessDeniedException.class);

        when(service.list(null)).thenReturn(List.of(sos()));
        assertThat(controller.active(null, "coord-1", "COORDINADOR")).hasSize(1);
        when(service.list(List.of(SosStatus.ACTIVE))).thenReturn(List.of());
        assertThat(controller.active(List.of(SosStatus.ACTIVE), "coord-1", "COORDINADOR")).isEmpty();
        when(service.acknowledge(1L, "admin-1")).thenReturn(sos());
        assertThat(controller.acknowledge(1L, "admin-1", "ADMIN").getId()).isEqualTo(1L);
        when(service.resolve(1L, "admin-1", "Atendido")).thenReturn(sos());
        assertThat(controller.resolve(1L, "Atendido", "admin-1", "ADMIN").getId()).isEqualTo(1L);
    }

    @Test
    void onlyADriverCancelsAndTheReasonIsOptional() {
        assertThatThrownBy(() -> controller.cancel(1L, null, null, null)).isInstanceOf(AlertUnauthenticatedException.class);
        assertThatThrownBy(() -> controller.cancel(1L, null, "coord-1", "COORDINADOR")).isInstanceOf(SosAccessDeniedException.class);

        SosAlert cancelled = sos();
        cancelled.setStatus(SosStatus.CANCELLED);
        when(service.cancel(eq(1L), eq("driver-ana"), isNull())).thenReturn(cancelled);
        when(service.cancel(1L, "driver-ana", "Falsa alarma")).thenReturn(cancelled);

        assertThat(controller.cancel(1L, null, "driver-ana", "CONDUCTOR").getStatus()).isEqualTo("CANCELLED");
        assertThat(controller.cancel(1L, new SosCancelRequest("Falsa alarma"), "driver-ana", "CONDUCTOR").getStatus())
                .isEqualTo("CANCELLED");
    }
}
