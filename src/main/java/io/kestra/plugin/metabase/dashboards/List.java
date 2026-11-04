package io.kestra.plugin.metabase.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.metabase.AbstractMetabaseTask;
import io.kestra.plugin.metabase.MetabaseApi;
import io.kestra.plugin.metabase.MetabaseClient;
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
    title = "List Metabase dashboards",
    description = """
        Retrieves the list of all accessible dashboards (`GET /api/dashboard`) from Metabase."""
)
@Plugin(
    examples = {
        @Example(
            title = "List all dashboards in Metabase",
            full = true,
            code = """
                id: metabase_list_dashboards
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.metabase.dashboards.List
                    url: "{{ secret('METABASE_URL') }}"
                    apiKey: "{{ secret('METABASE_API_KEY') }}"
                """
        )
    }
)
public class List extends AbstractMetabaseTask implements RunnableTask<DashboardsOutput> {

    @Override
    public DashboardsOutput run(RunContext runContext) throws Exception {
        runContext.logger().info("Fetching list of Metabase dashboards");

        try (var client = createClient(runContext)) {
            var dashboards = client.get(MetabaseApi.DASHBOARDS, MetabaseClient.OBJECT_LIST);

            runContext.logger().info("Retrieved {} Metabase dashboards", dashboards.size());

            return DashboardsOutput.builder()
                .dashboards(dashboards)
                .size(dashboards.size())
                .build();
        }
    }
}
