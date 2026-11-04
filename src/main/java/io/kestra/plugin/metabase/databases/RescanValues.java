package io.kestra.plugin.metabase.databases;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.metabase.AbstractMetabaseTask;
import io.kestra.plugin.metabase.models.DatabaseActionResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger Metabase database field values rescan",
    description = """
            Triggers a field values rescan (`POST /api/database/{id}/rescan_values`) for a specified database in Metabase."""
)
@Plugin(
    examples = {
        @Example(
            title = "Rescan database field values after refreshing table data",
            full = true,
            code = """
                id: metabase_rescan_values
                namespace: company.team

                tasks:
                  - id: rescan
                    type: io.kestra.plugin.metabase.databases.RescanValues
                    url: "{{ secret('METABASE_URL') }}"
                    apiKey: "{{ secret('METABASE_API_KEY') }}"
                    databaseId: 3
                """
        )
    }
)
public class RescanValues extends AbstractMetabaseTask implements RunnableTask<RescanValues.Output> {

    @Schema(
        title = "Database ID",
        description = """
            The ID of the Metabase database to rescan field values for."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<Integer> databaseId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rDatabaseId = runContext.render(this.databaseId).as(Integer.class)
            .orElseThrow(() -> new IllegalArgumentException("Property 'databaseId' is required, set the id of the Metabase database."));
        runContext.logger().info("Triggering field values rescan for Metabase database ID {}", rDatabaseId);

        try (var client = createClient(runContext)) {
            client.post("/api/database/%d/rescan_values".formatted(rDatabaseId), null, DatabaseActionResponse.class);
        }

        return Output.builder()
            .databaseId(rDatabaseId)
            .success(true)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Database ID",
            description = """
            The ID of the database that was rescanned."""
        )
        private final Integer databaseId;

        @Schema(
            title = "Operation success",
            description = """
            Whether the field values rescan was successfully triggered."""
        )
        private final Boolean success;
    }
}
