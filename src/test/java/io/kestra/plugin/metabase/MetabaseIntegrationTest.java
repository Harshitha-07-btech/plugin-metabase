package io.kestra.plugin.metabase;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.metabase.queries.Run;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.BufferedInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Runs against a real Metabase, so Docker is required. Database 1 is Metabase's built-in sample database.
@KestraTest
@Testcontainers
class MetabaseIntegrationTest {
    private static final int PORT = 3000;
    private static final String EMAIL = "admin@example.com";
    private static final String PASSWORD = "Kestra-Test-9x!";
    private static final int SAMPLE_DATABASE_ID = 1;
    private static final long SAMPLE_ORDERS_ROWS = 18760;

    @Container
    private static final GenericContainer<?> METABASE = new GenericContainer<>(DockerImageName.parse("metabase/metabase:v0.53.5"))
        .withExposedPorts(PORT)
        .waitingFor(Wait.forHttp("/api/health").forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(15));

    private static String url;

    @Inject
    private RunContextFactory runContextFactory;

    @BeforeAll
    static void setUpMetabase() throws Exception {
        url = "http://%s:%d".formatted(METABASE.getHost(), METABASE.getMappedPort(PORT));
        var mapper = JacksonMapper.ofJson();
        var http = HttpClient.newHttpClient();

        var properties = http.send(HttpRequest.newBuilder(URI.create("%s/api/session/properties".formatted(url))).build(), HttpResponse.BodyHandlers.ofString());
        var token = mapper.readTree(properties.body()).path("setup-token").asText();

        var setup = Map.of(
            "token", token,
            "user", Map.of("first_name", "Kestra", "last_name", "Test", "email", EMAIL, "password", PASSWORD),
            "prefs", Map.of("site_name", "Kestra", "allow_tracking", false)
        );
        var response = http.send(
            HttpRequest.newBuilder(URI.create("%s/api/setup".formatted(url)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(setup)))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.body(), response.statusCode(), is(200));
    }

    private Run.RunBuilder<?, ?> query(String sql) {
        return Run.builder()
            .url(Property.ofValue(url))
            .username(Property.ofValue(EMAIL))
            .password(Property.ofValue(PASSWORD))
            .databaseId(Property.ofValue(SAMPLE_DATABASE_ID))
            .nativeQuery(Property.ofValue(sql));
    }

    @Test
    void usernamePasswordAuthRunsNativeQuery() throws Exception {
        var output = query("SELECT COUNT(*) AS TOTAL FROM ORDERS").build().run(runContextFactory.of(Map.of()));

        assertThat(output.getRows().getFirst().get("TOTAL"), is((int) SAMPLE_ORDERS_ROWS));
    }

    @Test
    void failedNativeQueryThrowsMetabaseError() {
        var task = query("SELECT * FROM NO_SUCH_TABLE").build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(e.getMessage(), containsString("NO_SUCH_TABLE"));
    }

    @Test
    void inlineFetchFailsOnTruncatedResult() {
        var task = query("SELECT * FROM ORDERS").build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(e.getMessage(), containsString("STORE"));
    }

    @Test
    void storeStreamsEveryRowThroughExport() throws Exception {
        var runContext = runContextFactory.of(Map.of());
        var output = query("SELECT * FROM ORDERS").fetchType(Property.ofValue(FetchType.STORE)).build().run(runContext);

        var count = new AtomicLong();
        try (var stream = new BufferedInputStream(runContext.storage().getFile(output.getUri()), FileSerde.BUFFER_SIZE)) {
            FileSerde.read(stream, row -> count.incrementAndGet());
        }

        assertThat(output.getSize(), is(SAMPLE_ORDERS_ROWS));
        assertThat(count.get(), is(SAMPLE_ORDERS_ROWS));
    }
}
