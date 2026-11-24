package escuelaing.edu.co.truckcar.community_alerts.service;

import com.fasterxml.jackson.databind.JsonNode;
import escuelaing.edu.co.truckcar.community_alerts.exception.CompanyLookupUnavailableException;
import escuelaing.edu.co.truckcar.community_alerts.exception.SosAccessDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.Set;

/**
 * Conductores con la afiliación ACTIVA a la empresa de la cuenta TRANSPORTADORA. Se le pregunta a User-Identity con el
 * mismo token del usuario (GET /api/v1/companies/me/drivers): User-Identity valida el token y la propiedad de la
 * empresa, así que una cuenta no puede ver los SOS de conductores de otra empresa.
 */
@Slf4j
@Component
public class CompanyDriversClient {

    private final RestClient restClient;

    public CompanyDriversClient(RestClient.Builder builder,
                                @Value("${truckcar.services.user-identity-url:http://localhost:8080}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public Set<String> activeDriverIds(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SosAccessDeniedException("Se requiere la sesión de la transportadora para ver los SOS de su flota");
        }
        JsonNode drivers;
        try {
            drivers = restClient.get().uri("/api/v1/companies/me/drivers")
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new SosAccessDeniedException("Esta cuenta no tiene una empresa transportadora");
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            throw new SosAccessDeniedException("Solo una cuenta de transportadora puede ver los SOS de su flota");
        } catch (Exception e) {
            log.warn("No se pudo consultar los conductores de la empresa en User-Identity: {}", e.getClass().getSimpleName());
            throw new CompanyLookupUnavailableException("No se pudo confirmar su flota; inténtelo de nuevo en unos segundos");
        }
        Set<String> ids = new HashSet<>();
        if (drivers != null) {
            drivers.forEach(d -> {
                if ("ACTIVA".equals(d.path("affiliationStatus").asText()) && d.hasNonNull("id")) {
                    ids.add(d.get("id").asText());
                }
            });
        }
        return Set.copyOf(ids);
    }
}
