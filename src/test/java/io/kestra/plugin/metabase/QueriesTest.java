package io.kestra.plugin.metabase;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.metabase.queries.Run;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class QueriesTest {

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
    void testQueryRunFetch() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(1))
            .nativeQuery(Property.ofValue("SELECT COUNT(*) AS total FROM users"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(1L));
        assertThat(output.getRows(), hasSize(1));
        assertThat(output.getRows().getFirst().get("total"), is(42));
    }

    @Test
    void testQueryRunFetchOne() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(1))
            .nativeQuery(Property.ofValue("SELECT COUNT(*) AS total FROM users"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(1L));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().get("total"), is(42));
    }

    @Test
    void testQueryRunStore() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(1))
            .nativeQuery(Property.ofValue("SELECT COUNT(*) AS total FROM users"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(2L));
        assertThat(output.getUri(), notNullValue());

        var deserializedRows = new ArrayList<Object>();
        try (var is = new BufferedInputStream(runContext.storage().getFile(output.getUri()), FileSerde.BUFFER_SIZE)) {
            FileSerde.read(is, deserializedRows::add);
        }

        assertThat(deserializedRows, hasSize(2));
        @SuppressWarnings("unchecked")
        var first = (Map<String, Object>) deserializedRows.getFirst();
        assertThat(first.get("total"), is(42));
    }

    @Test
    void testQueryRunFailedQueryThrowsMetabaseError() {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(1))
            .nativeQuery(Property.ofValue("SELECT * FROM FAIL_ME"))
            .build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(e.getMessage(), containsString("missing_table"));
    }

    @Test
    void testQueryRunStoreUsesExportEndpointWithQueryForm() throws Exception {
        var task = Run.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(1))
            .nativeQuery(Property.ofValue("SELECT 1"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        task.run(runContextFactory.of(Map.of()));

        var request = server.getRecordedRequests().getFirst();
        assertThat(request.path(), is("/api/dataset/json"));
        assertThat(request.headers().get("content-type"), startsWith("application/x-www-form-urlencoded"));
        assertThat(request.body(), containsString("query="));
    }
}
