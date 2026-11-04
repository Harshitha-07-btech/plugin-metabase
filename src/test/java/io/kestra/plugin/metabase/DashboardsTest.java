package io.kestra.plugin.metabase;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.flows.GenericFlow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.repositories.FlowRepositoryInterface;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.metabase.dashboards.DashboardsOutput;
import io.kestra.plugin.metabase.dashboards.Get;
import io.kestra.plugin.metabase.dashboards.List;
import io.kestra.plugin.metabase.dashboards.Trigger;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
class DashboardsTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private FlowRepositoryInterface flowRepository;

    private MetabaseMockServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MetabaseMockServer();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testDashboardList() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());

        var task = List.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .build();

        DashboardsOutput output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getDashboards(), hasSize(2));
        assertThat(output.getDashboards().getFirst().get("name"), is("Executive Overview"));
        assertThat(output.getDashboards().get(1).get("name"), is("Operations"));
    }

    @Test
    void testDashboardGet() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of());

        var task = Get.builder()
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .dashboardId(Property.ofValue(1))
            .build();

        Get.Output output = task.run(runContext);

        assertThat(output.getDashboard(), notNullValue());
        assertThat(output.getDashboard().get("id"), is(1));
        assertThat(output.getDashboard().get("name"), is("Executive Overview"));
        assertThat(output.getDashboard().containsKey("dashcards"), is(false));
        assertThat(output.getCards(), hasSize(2));
        assertThat(output.getCards().getFirst().get("id"), is(11));
    }

    @Test
    void testDashboardTriggerWatermarkFlow() throws Exception {
        var tenantId = IdUtils.create();
        var namespace = "io.kestra.%s".formatted(IdUtils.create());
        var flowId = "flow-%s".formatted(IdUtils.create());
        var triggerId = "trigger-%s".formatted(IdUtils.create());

        var flow = Flow.builder()
            .tenantId(tenantId)
            .id(flowId)
            .namespace(namespace)
            .revision(1)
            .tasks(Collections.emptyList())
            .build();
        flowRepository.create(GenericFlow.of(flow));

        var trigger = Trigger.builder()
            .id(triggerId)
            .type(Trigger.class.getName())
            .url(Property.ofValue(server.getUrl()))
            .apiKey(Property.ofValue("test-api-key"))
            .onStartup(Property.ofValue(false))
            .build();

        var triggerContext = TriggerContext.builder()
            .tenantId(tenantId)
            .triggerId(triggerId)
            .namespace(namespace)
            .flowId(flowId)
            .build();

        var conditionContext = conditionContext(flow, trigger, triggerContext);

        // 1. First evaluation: watermark is empty, onStartup=false -> initializes watermark to max (2026-10-20), returns empty
        Optional<Execution> eval1 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(eval1.isEmpty(), is(true));

        // 2. Add a new dashboard updated on 2026-10-31
        server.addDashboard(3, "Real-time Sales", "2026-10-31T08:00:00Z");

        // 3. Second evaluation: detects new dashboard, returns Execution
        Optional<Execution> eval2 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(eval2.isPresent(), is(true));
        Execution execution = eval2.get();
        assertThat(execution.getTrigger(), notNullValue());
        @SuppressWarnings("unchecked")
        var triggerVariables = (Map<String, Object>) execution.getTrigger().getVariables();
        assertThat(triggerVariables.get("size"), is(1));

        // 4. Third evaluation: no newer dashboard, returns empty
        Optional<Execution> eval3 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(eval3.isEmpty(), is(true));
    }

    @Test
    void testTriggerWatermarkIsScopedPerFlow() throws Exception {
        var tenantId = IdUtils.create();
        var namespace = "io.kestra.%s".formatted(IdUtils.create());
        var triggerId = "trigger-%s".formatted(IdUtils.create());

        var firstFlow = Flow.builder().tenantId(tenantId).id("flow-a").namespace(namespace).revision(1).tasks(Collections.emptyList()).build();
        var secondFlow = Flow.builder().tenantId(tenantId).id("flow-b").namespace(namespace).revision(1).tasks(Collections.emptyList()).build();

        var firstTrigger = Trigger.builder().id(triggerId).type(Trigger.class.getName())
            .url(Property.ofValue(server.getUrl())).apiKey(Property.ofValue("test-api-key")).build();
        var secondTrigger = Trigger.builder().id(triggerId).type(Trigger.class.getName())
            .url(Property.ofValue(server.getUrl())).apiKey(Property.ofValue("test-api-key")).onStartup(Property.ofValue(true)).build();

        var firstContext = TriggerContext.builder().tenantId(tenantId).triggerId(triggerId).namespace(namespace).flowId("flow-a").build();
        var secondContext = TriggerContext.builder().tenantId(tenantId).triggerId(triggerId).namespace(namespace).flowId("flow-b").build();

        firstTrigger.evaluate(conditionContext(firstFlow, firstTrigger, firstContext), firstContext);

        // flow-b has its own watermark, so onStartup still fires for it even though flow-a already stored one
        var eval = secondTrigger.evaluate(conditionContext(secondFlow, secondTrigger, secondContext), secondContext);
        assertThat(eval.isPresent(), is(true));
    }

    @Test
    void firstDashboardFiresWhenTriggerStartedWithNone() throws Exception {
        server.clearDashboards();
        var tenantId = IdUtils.create();
        var namespace = "io.kestra.%s".formatted(IdUtils.create());
        var triggerId = "trigger-%s".formatted(IdUtils.create());
        var flow = Flow.builder().tenantId(tenantId).id("flow-empty").namespace(namespace).revision(1).tasks(Collections.emptyList()).build();
        var trigger = Trigger.builder().id(triggerId).type(Trigger.class.getName())
            .url(Property.ofValue(server.getUrl())).apiKey(Property.ofValue("test-api-key")).build();
        var triggerContext = TriggerContext.builder().tenantId(tenantId).triggerId(triggerId).namespace(namespace).flowId("flow-empty").build();

        assertThat(trigger.evaluate(conditionContext(flow, trigger, triggerContext), triggerContext).isEmpty(), is(true));

        server.addDashboard(9, "First dashboard", "2026-11-04T08:00:00Z");
        assertThat(trigger.evaluate(conditionContext(flow, trigger, triggerContext), triggerContext).isPresent(), is(true));
    }

    private ConditionContext conditionContext(Flow flow, Trigger trigger, TriggerContext triggerContext) throws Exception {
        var runContext = runContextFactory.of(flow, trigger);
        runContext = runContextFactory.initializer().forScheduler((io.kestra.core.runners.DefaultRunContext) runContext, triggerContext, trigger);
        return ConditionContext.builder().runContext(runContext).flow(flow).build();
    }
}
