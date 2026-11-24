package escuelaing.edu.co.truckcar.Community_Alerts.controller;

import escuelaing.edu.co.truckcar.community_alerts.controller.CompanySosController;
import escuelaing.edu.co.truckcar.community_alerts.exception.CompanyLookupUnavailableException;
import escuelaing.edu.co.truckcar.community_alerts.exception.GlobalExceptionHandler;
import escuelaing.edu.co.truckcar.community_alerts.model.SosAlert;
import escuelaing.edu.co.truckcar.community_alerts.model.SosStatus;
import escuelaing.edu.co.truckcar.community_alerts.service.CompanyDriversClient;
import escuelaing.edu.co.truckcar.community_alerts.service.SosService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SOS de la flota de una transportadora: solo los de sus conductores ACTIVA. */
class CompanySosControllerTest {

    private final SosService service = mock(SosService.class);
    private final CompanyDriversClient companyDrivers = mock(CompanyDriversClient.class);
    private MockMvc mvc;

    private static SosAlert sos(long id, String driverId) {
        SosAlert s = new SosAlert();
        s.setId(id);
        s.setDriverId(driverId);
        s.setStatus(SosStatus.ACTIVE);
        return s;
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CompanySosController(service, companyDrivers))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        when(service.list(null)).thenReturn(List.of(sos(1, "ana"), sos(2, "jorge"), sos(3, "pedro")));
        when(companyDrivers.activeDriverIds("Bearer andinos")).thenReturn(Set.of("ana", "luis"));
    }

    @Test
    void aCompanySeesOnlyTheSosOfItsOwnDrivers() throws Exception {
        mvc.perform(get("/api/v1/alerts/sos/company").header("X-Auth-User", "dueno").header("X-Auth-Role", "TRANSPORTADORA")
                        .header("Authorization", "Bearer andinos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].driverId", containsInAnyOrder("ana")));
    }

    @Test
    void otherRolesNoSessionAndAnOutageAreRejected() throws Exception {
        mvc.perform(get("/api/v1/alerts/sos/company").header("X-Auth-User", "x").header("X-Auth-Role", "CONDUCTOR")
                        .header("Authorization", "Bearer andinos"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/alerts/sos/company").header("Authorization", "Bearer andinos"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        when(companyDrivers.activeDriverIds("Bearer caido")).thenThrow(new CompanyLookupUnavailableException("caído"));
        mvc.perform(get("/api/v1/alerts/sos/company").header("X-Auth-User", "x").header("X-Auth-Role", "TRANSPORTADORA")
                        .header("Authorization", "Bearer caido"))
                .andExpect(status().isServiceUnavailable());
    }
}
