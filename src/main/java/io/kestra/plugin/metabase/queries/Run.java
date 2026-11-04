package io.kestra.plugin.metabase.queries;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.metabase.AbstractMetabaseTask;
import io.kestra.plugin.metabase.MetabaseApi;
import io.kestra.plugin.metabase.QueryOutput;
import io.kestra.plugin.metabase.QueryOutputHelper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a native Metabase query",
    description = """
        Executes a native SQL query against a database connected to Metabase.

        `FETCH`, `FETCH_ONE` and `NONE` call `POST /api/dataset`, which Metabase caps at 2,000 rows.
        `STORE` calls the export endpoint `POST /api/dataset/json` and streams every row to internal storage.
        The exported row keys are the ones Metabase writes in its JSON export, which can differ from the column names returned by the other fetch types."""
)
@Plugin(
    examples = {
        @Example(
            title = "Execute a native query and stream rows to Kestra storage",
            full = true,
            code = """
                id: metabase_native_query
                namespace: company.team

                tasks:
                  - id: run
                    type: io.kestra.plugin.metabase.queries.Run
                    url: "{{ secret('METABASE_URL') }}"
                    apiKey: "{{ secret('METABASE_API_KEY') }}"
                    databaseId: 2
                    nativeQuery: "SELECT id, name, created_at FROM orders WHERE status = 'COMPLETED'"
                    fetchType: STORE
                """
        ),
        @Example(
            title = "Fetch single row from a native query",
            full = true,
            code = """
                id: metabase_native_fetch_one
                namespace: company.team

                tasks:
                  - id: get_count
                    type: io.kestra.plugin.metabase.queries.Run
                    url: "{{ secret('METABASE_URL') }}"
                    apiKey: "{{ secret('METABASE_API_KEY') }}"
                    databaseId: 2
                    nativeQuery: "SELECT COUNT(*) as total FROM users"
                    fetchType: FETCH_ONE
                """
        )
    }
)
public class Run extends AbstractMetabaseTask implements RunnableTask<QueryOutput> {

    @Schema(
        title = "Database ID",
        description = """
            The ID of the Metabase database to run the query on."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<Integer> databaseId;

    @Schema(
        title = "Native SQL query",
        description = """
            The native SQL query to run."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> nativeQuery;

    @Schema(
        title = "Template tags",
        description = """
            Optional Metabase template tags (variable bindings) for the native query."""
    )
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> templateTags;

    @Schema(
        title = "Fetch type",
        description = """
            How results are handled: `FETCH_ONE` (first row), `FETCH` (all rows inline), `STORE` (stream into internal storage as an Ion file) or `NONE` (only the row count).
            `FETCH_ONE`, `FETCH` and `NONE` are limited to the 2,000 rows Metabase returns from `/api/dataset`. Use `STORE` for larger results."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public QueryOutput run(RunContext runContext) throws Exception {
        var rDatabaseId = runContext.render(this.databaseId).as(Integer.class)
            .orElseThrow(() -> new IllegalArgumentException("Property 'databaseId' is required, set the id of the Metabase database."));
        var rQuery = runContext.render(this.nativeQuery).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Property 'nativeQuery' is required, set the SQL to run."));
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rTemplateTags = runContext.render(this.templateTags).asMap(String.class, Object.class);

        runContext.logger().info("Executing native query on database ID {} with fetchType {}", rDatabaseId, rFetchType);

        var datasetQuery = Map.of(
            "database", rDatabaseId,
            "type", "native",
            "native", Map.of("query", rQuery, "template-tags", rTemplateTags)
        );

        var exportForm = Map.<String, Object>of("query", JacksonMapper.ofJson().writeValueAsString(datasetQuery));

        try (var client = createClient(runContext)) {
            return QueryOutputHelper.fetch(runContext, client, rFetchType, MetabaseApi.DATASET, datasetQuery, exportForm);
        }
    }
}
