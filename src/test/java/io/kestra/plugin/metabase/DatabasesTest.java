package io.kestra.plugin.metabase;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.metabase.databases.RescanValues;
import io.kestra.plugin.metabase.databases.SyncSchema;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class DatabasesTest {

    @Inject
    private RunContextFactory runContextFactory;

    private MetabaseMockServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MetabaseMockServer();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testSyncSchema() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());

        var task = SyncSchema.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(1))
            .build();

        SyncSchema.Output output = task.run(runContext);

        assertThat(output.getSuccess(), is(true));
        assertThat(output.getDatabaseId(), is(1));

        assertThat(server.getRecordedRequests(), hasSize(1));
        assertThat(server.getRecordedRequests().getFirst().path(), is("/api/database/1/sync_schema"));
    }

    @Test
    void testRescanValues() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());

        var task = RescanValues.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .databaseId(Property.ofValue(1))
            .build();

        RescanValues.Output output = task.run(runContext);

        assertThat(output.getSuccess(), is(true));
        assertThat(output.getDatabaseId(), is(1));

        assertThat(server.getRecordedRequests(), hasSize(1));
        assertThat(server.getRecordedRequests().getFirst().path(), is("/api/database/1/rescan_values"));
    }
}
