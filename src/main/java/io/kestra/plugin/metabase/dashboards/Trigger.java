package io.kestra.plugin.metabase.dashboards;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.metabase.MetabaseApi;
import io.kestra.plugin.metabase.MetabaseClient;
import io.kestra.plugin.metabase.MetabaseConnectionInterface;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Metabase dashboard polling trigger",
    description = """
        Triggers a flow when a Metabase dashboard is created or updated since the last evaluation. The watermark is kept in the namespace KV store."""
)
@Plugin(
    examples = {
        @Example(
            title = "Watch for dashboard changes in Metabase and trigger a flow",
            full = true,
            code = """
                id: on_metabase_change
                namespace: company.team

                triggers:
                  - id: watch
                    type: io.kestra.plugin.metabase.dashboards.Trigger
                    url: "{{ secret('METABASE_URL') }}"
                    apiKey: "{{ secret('METABASE_API_KEY') }}"
                    interval: PT5M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Metabase dashboard change detected. Changed count: {{ trigger.size }}"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<DashboardsOutput>, MetabaseConnectionInterface {

    @Schema(
        title = "Metabase server URL",
        description = """
            Base URL of the Metabase instance, for example `https://metabase.example.com` or `http://localhost:3000`."""
    )
    @PluginProperty(group = "connection")
    @NotNull
    private Property<String> url;

    @Schema(
        title = "Metabase API key",
        description = """
            API key sent in the `x-api-key` header. Requires Metabase v0.47 or later.
            Set exactly one authentication mode: `apiKey`, `sessionToken`, or both `username` and `password`."""
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> apiKey;

    @Schema(
        title = "Metabase session token",
        description = """
            Session token sent in the `X-Metabase-Session` header.
            Set exactly one authentication mode: `apiKey`, `sessionToken`, or both `username` and `password`."""
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> sessionToken;

    @Schema(
        title = "Metabase username",
        description = """
            Username or email used to open a session through `POST /api/session`. Must be set together with `password`.
            The session is closed with `DELETE /api/session` when the evaluation ends."""
    )
    @PluginProperty(group = "connection")
    private Property<String> username;

    @Schema(
        title = "Metabase password",
        description = """
            Password used to open a session through `POST /api/session`. Must be set together with `username`."""
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> password;

    @Schema(
        title = "HTTP request timeout",
        description = """
            Connect and read timeout for each HTTP request made to Metabase. Defaults to 60 seconds."""
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<Duration> requestTimeout = Property.ofValue(DEFAULT_REQUEST_TIMEOUT);

    @Schema(
        title = "Polling interval",
        description = """
            The interval between polling checks. Defaults to 5 minutes."""
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private final Duration interval = Duration.ofMinutes(5);

    @Schema(
        title = "Trigger on startup",
        description = """
            If true, the first evaluation fires for all existing dashboards.
            If false (default), the first evaluation only records the watermark without firing."""
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<Boolean> onStartup = Property.ofValue(false);

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rOnStartup = runContext.render(this.onStartup).as(Boolean.class).orElse(false);

        var kvStore = runContext.namespaceKv(conditionContext.getFlow().getNamespace());
        var watermarkKey = watermarkKey(context.getFlowId(), context.getTriggerId());
        var lastWatermark = kvStore.getValue(watermarkKey)
            .map(kv -> kv.value() != null ? Instant.parse(kv.value().toString()) : null)
            .orElse(null);

        List<Map<String, Object>> dashboards;
        try (var client = createClient(runContext)) {
            dashboards = client.get(MetabaseApi.DASHBOARDS, MetabaseClient.OBJECT_LIST);
        }

        var stamped = dashboards.stream()
            .map(dashboard -> new StampedDashboard(dashboard, timestamp(dashboard)))
            .filter(dashboard -> dashboard.changedAt() != null)
            .toList();
        var maxTimestamp = stamped.stream().map(StampedDashboard::changedAt).max(Instant::compareTo).orElse(null);
        if (maxTimestamp == null) {
            // Without any dashboard yet, start from the epoch so the first one created later fires.
            if (lastWatermark == null) {
                kvStore.put(watermarkKey, new KVValueAndMetadata(null, Instant.EPOCH.toString()));
            }
            return Optional.empty();
        }

        if (lastWatermark == null) {
            kvStore.put(watermarkKey, new KVValueAndMetadata(null, maxTimestamp.toString()));
            if (!rOnStartup) {
                runContext.logger().info("Initialized Metabase dashboard watermark to {}", maxTimestamp);
                return Optional.empty();
            }
            return Optional.of(generateExecution(conditionContext, context, dashboards));
        }

        var changed = stamped.stream()
            .filter(dashboard -> dashboard.changedAt().isAfter(lastWatermark))
            .map(StampedDashboard::dashboard)
            .toList();
        if (changed.isEmpty()) {
            return Optional.empty();
        }

        kvStore.put(watermarkKey, new KVValueAndMetadata(null, maxTimestamp.toString()));
        runContext.logger().info("Found {} updated or created dashboards, advanced watermark to {}", changed.size(), maxTimestamp);

        return Optional.of(generateExecution(conditionContext, context, changed));
    }

    private Execution generateExecution(ConditionContext conditionContext, TriggerContext context, List<Map<String, Object>> dashboards) {
        var output = DashboardsOutput.builder().dashboards(dashboards).size(dashboards.size()).build();
        return TriggerService.generateExecution(this, conditionContext, context, output);
    }

    private record StampedDashboard(Map<String, Object> dashboard, Instant changedAt) {
    }

    // Length prefix keeps ("ab", "c") and ("a", "bc") from colliding on the same key.
    private static String watermarkKey(String flowId, String triggerId) {
        return "metabase_dashboards_watermark_%d_%s_%s".formatted(flowId.length(), flowId, triggerId);
    }

    private static Instant timestamp(Map<String, Object> dashboard) {
        var raw = dashboard.get("updated_at") != null ? dashboard.get("updated_at") : dashboard.get("created_at");
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.toString()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(
                "Metabase dashboard %s has an unexpected timestamp '%s'. Expected an ISO-8601 date with an offset.".formatted(dashboard.get("id"), raw), e
            );
        }
    }
}
