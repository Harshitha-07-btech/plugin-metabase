package io.kestra.plugin.metabase.databases;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.metabase.AbstractMetabaseTask;
import io.kestra.plugin.metabase.MetabaseApi;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
public abstract class AbstractDatabaseTask extends AbstractMetabaseTask implements RunnableTask<VoidOutput> {
    private static final String OK_STATUS = "ok";

    @Schema(
        title = "Database ID",
        description = """
            The ID of the Metabase database to act on."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<Integer> databaseId;

    protected abstract String action();

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        var rDatabaseId = runContext.render(this.databaseId).as(Integer.class)
            .orElseThrow(() -> new IllegalArgumentException("Property 'databaseId' is required, set the id of the Metabase database."));
        runContext.logger().info("Running '{}' on Metabase database ID {}", action(), rDatabaseId);

        var path = MetabaseApi.DATABASE_ACTION.formatted(rDatabaseId, action());
        try (var client = createClient(runContext)) {
            var response = client.post(path, null, Map.class);
            var status = response.get("status");
            if (!OK_STATUS.equals(status)) {
                throw new IllegalStateException(
                    "Metabase answered %s with status '%s' instead of '%s'. Check the database id and the Metabase server logs.".formatted(path, status, OK_STATUS)
                );
            }
        }

        return null;
    }
}
