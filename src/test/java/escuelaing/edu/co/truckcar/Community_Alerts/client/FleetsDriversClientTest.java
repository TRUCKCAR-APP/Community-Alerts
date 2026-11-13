package escuelaing.edu.co.truckcar.Community_Alerts.client;

import com.sun.net.httpserver.HttpServer;
import escuelaing.edu.co.truckcar.community_alerts.client.FleetsDriversClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Contra un fleets-drivers falso (servidor HTTP del JDK): el cliente nunca lanza y entiende el lastUpdate sin zona. */
class FleetsDriversClientTest {

    private HttpServer server;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<Long> delayMs = new AtomicReference<>(0L);
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private FleetsDriversClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        client = new FleetsDriversClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", 400, "America/Bogota");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsThePositionTheIdentityAndTheLocalTimestamp() {
        body.set("""
                {"driverId":"d-1","driverName":"Ana Gómez","vehiclePlate":"SKD-123","currentLatitude":5.07,
                 "currentLongitude":-75.52,"lastUpdate":"2026-11-12T08:30:00","currentLoadId":44}
                """);

        var snapshot = client.lastKnown("d-1").orElseThrow();

        assertThat(lastPath.get()).isEqualTo("/api/v1/fleets-drivers/drivers/d-1");
        assertThat(snapshot.hasPosition()).isTrue();
        assertThat(snapshot.latitude()).isEqualTo(5.07);
        assertThat(snapshot.driverName()).isEqualTo("Ana Gómez");
        assertThat(snapshot.vehiclePlate()).isEqualTo("SKD-123");
        assertThat(snapshot.currentLoadId()).isEqualTo(44L);
        assertThat(snapshot.recordedAt()).isEqualTo(
                LocalDateTime.parse("2026-11-12T08:30:00").atZone(ZoneId.of("America/Bogota")).toInstant());
    }

    @Test
    void aStateWithoutOrWithAnInvalidPositionHasNoPosition() {
        body.set("{\"driverName\":\"Ana\",\"currentLatitude\":null,\"currentLongitude\":-75.5}");
        assertThat(client.lastKnown("d-1").orElseThrow().hasPosition()).isFalse();

        body.set("{\"currentLatitude\":95.0,\"currentLongitude\":-75.5}");
        assertThat(client.lastKnown("d-1").orElseThrow().hasPosition()).isFalse();
    }

    @Test
    void notFoundErrorsAndTimeoutsGiveEmptyInsteadOfFailing() {
        status.set(404);
        body.set("{\"error\":\"no existe\"}");
        assertThat(client.lastKnown("d-1")).isEmpty();

        status.set(503);
        assertThat(client.lastKnown("d-1")).isEmpty();

        status.set(200);
        body.set("{\"currentLatitude\":5.0,\"currentLongitude\":-75.0}");
        delayMs.set(1500L);
        assertThat(client.lastKnown("d-1")).isEmpty();

        assertThat(client.lastKnown(null)).isEmpty();
        assertThat(client.lastKnown(" ")).isEmpty();
    }
}
