package io.kestra.plugin.metabase.databases;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "Trigger Metabase database schema sync",
    description = """
            Triggers a schema synchronization (`POST /api/database/{id}/sync_schema`) for a database. Metabase runs it asynchronously, the task does not wait for it to finish."""
)
@Plugin(
    examples = {
        @Example(
            title = "Sync database schema after a data warehouse load",
            full = true,
            code = """
                id: metabase_sync_schema
                namespace: company.team

                tasks:
                  - id: sync
                    type: io.kestra.plugin.metabase.databases.SyncSchema
                    url: "{{ secret('METABASE_URL') }}"
                    apiKey: "{{ secret('METABASE_API_KEY') }}"
                    databaseId: 3
                """
        )
    }
)
public class SyncSchema extends AbstractDatabaseTask {

    @Override
    protected String action() {
        return "sync_schema";
    }
}
