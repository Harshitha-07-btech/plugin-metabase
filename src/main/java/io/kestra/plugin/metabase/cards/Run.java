package io.kestra.plugin.metabase.cards;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a saved Metabase question",
    description = """
        Executes a saved Metabase question and returns or stores the resulting rows.

        `FETCH`, `FETCH_ONE` and `NONE` call `POST /api/card/{id}/query`, which Metabase caps at 2,000 rows.
        `STORE` calls the export endpoint `POST /api/card/{id}/query/json` and streams every row to internal storage.
        The exported row keys are the ones Metabase writes in its JSON export, which can differ from the column names returned by the other fetch types."""
)
@Plugin(
    examples = {
        @Example(
            title = "Run a saved question and store results in Kestra storage",
            full = true,
            code = """
                id: metabase_card_run
                namespace: company.team

                tasks:
                  - id: run
                    type: io.kestra.plugin.metabase.cards.Run
                    url: "{{ secret('METABASE_URL') }}"
                    apiKey: "{{ secret('METABASE_API_KEY') }}"
                    cardId: 42
                    fetchType: STORE
                """
        ),
        @Example(
            title = "Fetch rows of a saved question inline",
            full = true,
            code = """
                id: metabase_card_fetch
                namespace: company.team

                tasks:
                  - id: run
                    type: io.kestra.plugin.metabase.cards.Run
                    url: "{{ secret('METABASE_URL') }}"
                    apiKey: "{{ secret('METABASE_API_KEY') }}"
                    cardId: 10
                    fetchType: FETCH
                """
        )
    }
)
public class Run extends AbstractMetabaseTask implements RunnableTask<QueryOutput> {
    private static final String PARAMETERS = "parameters";

    @Schema(
        title = "Card (question) ID",
        description = """
            The ID of the saved question to run."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<Integer> cardId;

    @Schema(
        title = "Query parameters",
        description = """
            Optional parameter values passed to the question, in the Metabase `parameters` format."""
    )
    @PluginProperty(group = "main")
    private Property<List<Map<String, Object>>> parameters;

    @Schema(
        title = "Fetch type",
        description = """
            How results are handled: `FETCH_ONE` (first row), `FETCH` (all rows inline), `STORE` (stream into internal storage as an Ion file) or `NONE` (only the row count).
            `FETCH_ONE`, `FETCH` and `NONE` are limited to the 2,000 rows Metabase returns from `/api/card/{id}/query`. Use `STORE` for larger results."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public QueryOutput run(RunContext runContext) throws Exception {
        var rCardId = runContext.render(this.cardId).as(Integer.class)
            .orElseThrow(() -> new IllegalArgumentException("Property 'cardId' is required, set the id of the saved question."));
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rParameters = runContext.render(this.parameters).asList(Map.class);

        runContext.logger().info("Executing saved question ID {} with fetchType {}", rCardId, rFetchType);

        var body = new HashMap<String, Object>();
        var exportForm = new HashMap<String, Object>();
        if (!rParameters.isEmpty()) {
            body.put(PARAMETERS, rParameters);
            exportForm.put(PARAMETERS, JacksonMapper.ofJson().writeValueAsString(rParameters));
        }

        try (var client = createClient(runContext)) {
            return QueryOutputHelper.fetch(runContext, client, rFetchType, MetabaseApi.CARD_QUERY.formatted(rCardId), body, exportForm);
        }
    }
}
