package io.kestra.plugin.metabase;

import io.kestra.core.exceptions.KilledException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.metabase.cards.Run;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class CardsTest {

    @Inject
    private RunContextFactory runContextFactory;

    private MetabaseMockServer server;

    @BeforeEach
    void setUp() {
        server = new MetabaseMockServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void testCardRunFetch() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(10))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(2L));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().getFirst().get("id"), is(101));
        assertThat(output.getRows().getFirst().get("title"), is("First Item"));
        assertThat(output.getRows().get(1).get("id"), is(102));
        assertThat(output.getRows().get(1).get("title"), is("Second Item"));
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void testCardRunFetchOne() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(10))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(1L));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().get("id"), is(101));
        assertThat(output.getRow().get("title"), is("First Item"));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void testCardRunStore() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(10))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(3L));
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());

        var deserializedRows = new ArrayList<Object>();
        try (var is = new BufferedInputStream(runContext.storage().getFile(output.getUri()), FileSerde.BUFFER_SIZE)) {
            FileSerde.read(is, deserializedRows::add);
        }

        assertThat(deserializedRows, hasSize(3));
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) deserializedRows.getFirst();
        assertThat(first.get("id"), is(101));
        assertThat(first.get("title"), is("First Item"));
    }

    @Test
    void testCardRunNone() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(10))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(2L));
        assertThat(output.getRow(), nullValue());
        assertThat(output.getRows(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void testCardRunFailedQueryThrowsMetabaseError() {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(11))
            .build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(e.getMessage(), containsString("missing_table"));
    }

    @Test
    void testCardRunUnauthorizedExplainsCredentials() {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("bad-key"))
            .cardId(Property.ofValue(10))
            .build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(e.getMessage(), allOf(containsString("401"), containsString("credentials")));
    }

    @Test
    void testCardRunUnknownCardExplainsId() {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(999))
            .build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(e.getMessage(), allOf(containsString("404"), containsString("id")));
    }

    @Test
    void testCardRunStoreUsesExportEndpointWithFormParameters() throws Exception {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(10))
            .parameters(Property.ofValue(List.of(Map.of("type", "category", "value", "x"))))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        task.run(runContextFactory.of(Map.of()));

        var request = server.getRecordedRequests().getFirst();
        assertThat(request.path(), is("/api/card/10/query/json"));
        assertThat(request.headers().get("content-type"), startsWith("application/x-www-form-urlencoded"));
        assertThat(request.body(), allOf(containsString("parameters="), containsString("format_rows=false")));
    }

    @Test
    void testCardRunStoreIsNotCappedAt2000Rows() throws Exception {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(12))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getSize(), is((long) MetabaseMockServer.EXPORT_ROWS));
    }

    @Test
    void testCardRunStoreFailedExportThrowsMetabaseError() {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(13))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(e.getMessage(), containsString("missing_table"));
    }

    @Test
    void testTruncatedResultFailsExceptForFetchOne() throws Exception {
        for (var fetchType : List.of(FetchType.FETCH, FetchType.NONE)) {
            var task = Run.builder()
                .url(Property.ofValue(server.getUrl()))
                .apiKey(Property.ofValue("test-api-key"))
                .cardId(Property.ofValue(14))
                .fetchType(Property.ofValue(fetchType))
                .build();

            var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));
            assertThat(e.getMessage(), containsString("STORE"));
        }

        var fetchOne = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(14))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();
        assertThat(fetchOne.run(runContextFactory.of(Map.of())).getRow().get("id"), is(1));
    }

    @Test
    void testKillAbortsInFlightRequest() throws Exception {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(MetabaseMockServer.SLOW_CARD_ID))
            .build();

        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> task.run(runContextFactory.of(Map.of())));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !server.getRecordedRequests().isEmpty());

            task.kill();

            assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void killBeforeTheClientExistsStopsTheRun() {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .cardId(Property.ofValue(MetabaseMockServer.SLOW_CARD_ID))
            .build();

        task.kill();

        assertThrows(KilledException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(server.getRecordedRequests(), empty());
    }
}
