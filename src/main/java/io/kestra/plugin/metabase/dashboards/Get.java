package io.kestra.plugin.metabase.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.metabase.AbstractMetabaseTask;
import io.kestra.plugin.metabase.models.DashboardResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Get a Metabase dashboard",
    description = """
        Retrieves a dashboard (`GET /api/dashboard/{id}`) from Metabase. The cards are returned in `cards` and left out of `dashboard`."""
)
@Plugin(
    examples = {
        @Example(
            title = "Get details and embedded cards for a dashboard",
            full = true,
            code = """
                id: metabase_get_dashboard
                namespace: company.team

                tasks:
                  - id: get
                    type: io.kestra.plugin.metabase.dashboards.Get
                    url: "{{ secret('METABASE_URL') }}"
                    apiKey: "{{ secret('METABASE_API_KEY') }}"
                    dashboardId: 5
                """
        )
    }
)
public class Get extends AbstractMetabaseTask implements RunnableTask<Get.Output> {

    @Schema(
        title = "Dashboard ID",
        description = """
            The ID of the Metabase dashboard to retrieve."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<Integer> dashboardId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rDashboardId = runContext.render(this.dashboardId).as(Integer.class)
            .orElseThrow(() -> new IllegalArgumentException("Property 'dashboardId' is required, set the id of the Metabase dashboard."));
        runContext.logger().info("Fetching Metabase dashboard ID {}", rDashboardId);

        try (var client = createClient(runContext)) {
            var response = client.get("/api/dashboard/%d".formatted(rDashboardId), DashboardResponse.class);

            var cards = response.getDashcards() != null ? response.getDashcards()
                : response.getOrderedCards() != null ? response.getOrderedCards()
                : Collections.<Map<String, Object>>emptyList();

            var dashboard = new LinkedHashMap<>(response.toMap());
            dashboard.remove("dashcards");
            dashboard.remove("ordered_cards");

            return Output.builder()
                .dashboard(dashboard)
                .cards(cards)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Dashboard details",
            description = """
                Metadata of the Metabase dashboard, without its cards."""
        )
        private final Map<String, Object> dashboard;

        @Schema(
            title = "Dashboard cards",
            description = """
                The cards (dashcards) placed on the dashboard."""
        )
        private final List<Map<String, Object>> cards;
    }
}
