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
    title = "Trigger Metabase database field values rescan",
    description = """
            Triggers a field values rescan (`POST /api/database/{id}/rescan_values`) for a database. Metabase runs it asynchronously, the task does not wait for it to finish."""
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
public class RescanValues extends AbstractDatabaseTask {

    @Override
    protected String action() {
        return "rescan_values";
    }
}
