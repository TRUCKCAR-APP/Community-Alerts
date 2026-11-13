package escuelaing.edu.co.truckcar.Community_Alerts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import escuelaing.edu.co.truckcar.community_alerts.CommunityAlertsApplication;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOS de punta a punta con PostgreSQL (PostGIS) y Kafka reales (Testcontainers) y un fleets-drivers falso: el SOS
 * sin GPS se registra con la última posición conocida o como UNKNOWN (nunca 400, ni en línea ni en la cola offline),
 * su evento llega a truckcar.events.sos y el conductor puede anularlo.
 */
@Testcontainers
// El contexto se cierra al terminar la clase, antes de detener los contenedores (si no, el outbox sigue intentando).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = CommunityAlertsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SosResilienceIntegrationTest {

    private static final String SOS_TOPIC = "truckcar.events.sos";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("truckcar_db");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    /** fleets-drivers falso: driverId -> JSON del estado (sin entrada, 404). */
    private static final Map<String, String> FLEET_STATES = new ConcurrentHashMap<>();
    private static HttpServer fleets;

    @BeforeAll
    static void startFleets() throws IOException {
        fleets = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fleets.createContext("/api/v1/fleets-drivers/drivers/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String state = FLEET_STATES.get(path.substring(path.lastIndexOf('/') + 1));
            byte[] bytes = (state == null ? "{\"error\":\"sin estado\"}" : state).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(state == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fleets.start();
    }

    @AfterAll
    static void stopFleets() {
        fleets.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl() + "&currentSchema=community_alerts,public");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("truckcar.services.fleets-drivers-url",
                () -> "http://127.0.0.1:" + fleets.getAddress().getPort());
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();

    private static HttpHeaders as(String userId, String role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Auth-User", userId);
        headers.set("X-Auth-Role", role);
        return headers;
    }

    private ResponseEntity<JsonNode> post(String path, String json, String userId, String role) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, as(userId, role)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> patch(String path, String json, String userId, String role) {
        return rest.exchange(path, HttpMethod.PATCH, new HttpEntity<>(json, as(userId, role)), JsonNode.class);
    }

    @Test
    void anSosWithoutGpsUsesTheLastKnownPositionAndItsEventReachesKafka() throws Exception {
        String driver = "driver-" + UUID.randomUUID();
        FLEET_STATES.put(driver, """
                {"driverId":"%s","driverName":"Ana Gómez","vehiclePlate":"SKD-123","currentLatitude":5.07,
                 "currentLongitude":-75.52,"lastUpdate":"2026-11-12T08:30:00","currentLoadId":44}
                """.formatted(driver));

        ResponseEntity<JsonNode> response = post("/api/v1/alerts/sos",
                "{\"message\":\"Me quedé sin frenos\",\"driverName\":\"otro nombre\"}", driver, "CONDUCTOR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode sos = response.getBody();
        assertThat(sos.get("locationSource").asText()).isEqualTo("LAST_KNOWN");
        assertThat(sos.get("latitude").asDouble()).isEqualTo(5.07);
        assertThat(sos.get("driverName").asText()).isEqualTo("Ana Gómez");
        assertThat(sos.get("vehiclePlate").asText()).isEqualTo("SKD-123");
        assertThat(sos.get("loadId").asLong()).isEqualTo(44L);
        assertThat(sos.get("locationRecordedAt").isNull()).isFalse();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT location_source, latitude, longitude FROM community_alerts.sos_alerts WHERE id = ?",
                sos.get("id").asLong());
        assertThat(row.get("location_source")).isEqualTo("LAST_KNOWN");

        JsonNode event = awaitEvent(sos.get("id").asLong(), "alert.sos.triggered");
        assertThat(event.path("payload").path("locationSource").asText()).isEqualTo("LAST_KNOWN");
    }

    @Test
    void withoutGpsAndWithoutAnyKnownPositionTheSosIsStoredAsUnknownNever400() {
        String driver = "driver-" + UUID.randomUUID();

        ResponseEntity<JsonNode> response = post("/api/v1/alerts/sos",
                "{\"latitude\":\"abc\",\"longitude\":null,\"loadId\":\"x\",\"message\":\"" + "x".repeat(800) + "\"}",
                driver, "CONDUCTOR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode sos = response.getBody();
        assertThat(sos.get("locationSource").asText()).isEqualTo("UNKNOWN");
        assertThat(sos.get("latitude").isNull()).isTrue();
        assertThat(sos.get("message").asText()).hasSize(500);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT location_source, latitude FROM community_alerts.sos_alerts WHERE id = ?", sos.get("id").asLong());
        assertThat(row.get("location_source")).isEqualTo("UNKNOWN");
        assertThat(row.get("latitude")).isNull();
    }

    @Test
    void anOfflineSosWithoutCoordinatesIsAcceptedNotRejected() {
        String driver = "driver-" + UUID.randomUUID();
        UUID clientEventId = UUID.randomUUID();
        String batch = """
                {"events":[{"clientEventId":"%s","kind":"alert.sos","occurredAt":"%s","seq":1,
                            "payload":{"message":"Sin señal en el túnel"}}]}
                """.formatted(clientEventId, Instant.now().minusSeconds(120));

        ResponseEntity<JsonNode> response = post("/api/v1/alerts/sync/batch", batch, driver, "CONDUCTOR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("rejected")).isEmpty();
        assertThat(response.getBody().get("accepted").get(0).asText()).isEqualTo(clientEventId.toString());
        assertThat(jdbc.queryForObject(
                "SELECT location_source FROM community_alerts.sos_alerts WHERE client_event_id = ?",
                String.class, clientEventId)).isEqualTo("UNKNOWN");
    }

    @Test
    void theDriverCancelsAFalseAlarmAndTheTowerCanFilterByStatus() throws Exception {
        String driver = "driver-" + UUID.randomUUID();
        long id = post("/api/v1/alerts/sos", "{\"latitude\":4.61,\"longitude\":-74.08}", driver, "CONDUCTOR")
                .getBody().get("id").asLong();

        ResponseEntity<JsonNode> other = patch("/api/v1/alerts/sos/" + id + "/cancel", null, "driver-otro", "CONDUCTOR");
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> cancelled = patch("/api/v1/alerts/sos/" + id + "/cancel",
                "{\"reason\":\"Lo toqué sin querer\"}", driver, "CONDUCTOR");
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.getBody().get("cancellationReason").asText()).isEqualTo("Lo toqué sin querer");

        // Idempotente y sin cuerpo.
        assertThat(patch("/api/v1/alerts/sos/" + id + "/cancel", null, driver, "CONDUCTOR").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(awaitEvent(id, "alert.sos.cancelled").path("payload").path("status").asText()).isEqualTo("CANCELLED");

        ResponseEntity<JsonNode> open = rest.exchange("/api/v1/alerts/sos", HttpMethod.GET,
                new HttpEntity<>(as("coord-1", "COORDINADOR")), JsonNode.class);
        assertThat(ids(open.getBody())).doesNotContain(id);
        ResponseEntity<JsonNode> cancelledList = rest.exchange("/api/v1/alerts/sos?status=CANCELLED", HttpMethod.GET,
                new HttpEntity<>(as("coord-1", "COORDINADOR")), JsonNode.class);
        assertThat(ids(cancelledList.getBody())).contains(id);
    }

    @Test
    void aResolvedSosCannotBeCancelled() {
        String driver = "driver-" + UUID.randomUUID();
        long id = post("/api/v1/alerts/sos", "{\"latitude\":4.61,\"longitude\":-74.08}", driver, "CONDUCTOR")
                .getBody().get("id").asLong();
        patch("/api/v1/alerts/sos/" + id + "/resolve?notes=Atendido", null, "coord-1", "COORDINADOR");

        ResponseEntity<JsonNode> response = patch("/api/v1/alerts/sos/" + id + "/cancel", null, driver, "CONDUCTOR");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.get("id").asLong()));
        return ids;
    }

    /** Lee el tópico SOS desde el principio hasta encontrar el evento del SOS indicado (máx. 30 s). */
    private JsonNode awaitEvent(long sosId, String eventType) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(SOS_TOPIC));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode value = mapper.readTree(record.value());
                    JsonNode envelope = value.isTextual() ? mapper.readTree(value.asText()) : value;
                    if (eventType.equals(envelope.path("eventType").asText())
                            && envelope.path("payload").path("id").asLong() == sosId) {
                        return envelope;
                    }
                }
            }
        }
        throw new AssertionError("No llegó " + eventType + " del SOS #" + sosId + " a " + SOS_TOPIC);
    }
}
