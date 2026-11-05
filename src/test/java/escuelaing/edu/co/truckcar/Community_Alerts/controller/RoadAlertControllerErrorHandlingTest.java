package escuelaing.edu.co.truckcar.Community_Alerts.controller;

import escuelaing.edu.co.truckcar.community_alerts.controller.RoadAlertController;
import escuelaing.edu.co.truckcar.community_alerts.dto.response.RoadAlertResponse;
import escuelaing.edu.co.truckcar.community_alerts.exception.GlobalExceptionHandler;
import escuelaing.edu.co.truckcar.community_alerts.service.IRoadAlertService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Errores del cliente (cuerpo ilegible, tipos o media type incorrectos) responden 4xx, no 500. */
class RoadAlertControllerErrorHandlingTest {

    private static final String VALID_BODY = """
            {"alertType":"DERRUMBE","severity":"ALTA","title":"Derrumbe parcial","latitude":4.3,"longitude":-73.9}
            """;

    private final IRoadAlertService service = mock(IRoadAlertService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RoadAlertController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/alerts").header("X-Auth-User", "driver-ana")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alertType\":\"DERRUMBE\",\"title\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Cuerpo de la solicitud inválido"));
        verifyNoInteractions(service);
    }

    @Test
    void invalidUtf8BodyIsABadRequest() throws Exception {
        byte[] latin1 = "{\"alertType\":\"DERRUMBE\",\"severity\":\"ALTA\",\"title\":\"Vía\",\"latitude\":4.3,\"longitude\":-73.9}"
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        mvc.perform(post("/api/v1/alerts").header("X-Auth-User", "driver-ana")
                        .contentType(MediaType.APPLICATION_JSON).content(latin1))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownEnumValueIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/alerts").header("X-Auth-User", "driver-ana")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY.replace("DERRUMBE", "TORNADO")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongParameterTypeIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/alerts/nearby").param("latitude", "norte").param("longitude", "-73.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void frameworkErrorsKeepTheirStatus() throws Exception {
        mvc.perform(post("/api/v1/alerts").header("X-Auth-User", "driver-ana")
                        .contentType(MediaType.TEXT_PLAIN).content(VALID_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void aValidAlertIsStillCreated() throws Exception {
        when(service.createAlert(any())).thenReturn(RoadAlertResponse.builder().id(1L).build());
        mvc.perform(post("/api/v1/alerts").header("X-Auth-User", "driver-ana")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
    }
}
