package io.kestra.plugin.metabase;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.metabase.databases.RescanValues;
import io.kestra.plugin.metabase.databases.SyncSchema;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class DatabasesTest {

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
    void testSyncSchema() throws Exception {
        var task = SyncSchema.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(1))
            .build();

        assertThat(task.run(runContextFactory.of(Map.of())), nullValue());

        assertThat(server.getRecordedRequests(), hasSize(1));
        assertThat(server.getRecordedRequests().getFirst().path(), is("/api/database/1/sync_schema"));
    }

    @Test
    void testRescanValues() throws Exception {
        var task = RescanValues.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(1))
            .build();

        assertThat(task.run(runContextFactory.of(Map.of())), nullValue());

        assertThat(server.getRecordedRequests().getFirst().path(), is("/api/database/1/rescan_values"));
    }

    @Test
    void testStatusOtherThanOkFails() {
        var task = SyncSchema.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(2))
            .build();

        var e = assertThrows(IllegalStateException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(e.getMessage(), containsString("'error'"));
    }
}
