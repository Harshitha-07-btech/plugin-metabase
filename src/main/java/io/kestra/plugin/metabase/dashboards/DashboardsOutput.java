package io.kestra.plugin.metabase.dashboards;

import io.kestra.core.models.tasks.Output;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Output of the {@code List} task and the {@code Trigger}. Top-level because the task named {@code List} shadows {@code java.util.List} in its own file.
 */
@Builder
@Getter
public class DashboardsOutput implements Output {
    @Schema(
        title = "Dashboards",
        description = """
            The Metabase dashboards returned by the task, or the ones created or updated since the last evaluation for the trigger."""
    )
    private final List<Map<String, Object>> dashboards;

    @Schema(
        title = "Dashboards count",
        description = """
            Number of dashboards in `dashboards`."""
    )
    private final Integer size;
}
