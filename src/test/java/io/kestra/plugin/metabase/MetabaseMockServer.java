package io.kestra.plugin.metabase;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.serializers.JacksonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

public class MetabaseMockServer implements AutoCloseable {
    public static final int EXPORT_ROWS = 5000;
    public static final int SLOW_CARD_ID = 20;
    private static final Map<String, Object> FAILED_QUERY = Map.of(
        "status", "failed",
        "error", "ERROR: relation \"missing_table\" does not exist",
        "row_count", 0
    );

    private final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private final List<Map<String, Object>> dashboards = new CopyOnWriteArrayList<>();

    public record RecordedRequest(String method, String path, String body, Map<String, String> headers) {}

    public MetabaseMockServer() {
        dashboards.add(dashboard(1, "Executive Overview", "2026-10-01T10:00:00Z", "2026-10-15T12:00:00Z"));
        dashboards.add(dashboard(2, "Operations", "2026-10-05T08:00:00Z", "2026-10-20T14:30:00Z"));

        server.start();
        stubDashboards();

        server.stubFor(get("/api/dashboard/1").willReturn(json(200, Map.of(
            "id", 1,
            "name", "Executive Overview",
            "dashcards", List.of(
                Map.of("id", 11, "card_id", 10, "row", 0, "col", 0),
                Map.of("id", 12, "card_id", 20, "row", 1, "col", 0)
            )
        ))));

        server.stubFor(post("/api/session").willReturn(json(200, Map.of("id", "test-session-token-xyz"))));
        server.stubFor(delete("/api/session").willReturn(aResponse().withStatus(204)));
        server.stubFor(post("/api/database/1/sync_schema").willReturn(json(200, Map.of("status", "ok"))));
        server.stubFor(post("/api/database/1/rescan_values").willReturn(json(200, Map.of("status", "ok"))));
        server.stubFor(post("/api/database/2/sync_schema").willReturn(json(200, Map.of("status", "error"))));

        server.stubFor(post("/api/card/10/query").willReturn(json(200, completed(
            List.of(col("id"), col("title")),
            List.of(List.of(101, "First Item"), List.of(102, "Second Item"))
        ))));
        server.stubFor(post("/api/card/11/query").willReturn(json(202, FAILED_QUERY)));
        server.stubFor(post("/api/card/14/query").willReturn(json(200, truncated())));
        server.stubFor(post("/api/card/%d/query".formatted(SLOW_CARD_ID)).willReturn(json(200, FAILED_QUERY).withFixedDelay(15_000)));

        server.stubFor(post("/api/card/10/query/json").willReturn(json(200, List.of(
            Map.of("id", 101, "title", "First Item"),
            Map.of("id", 102, "title", "Second Item"),
            Map.of("id", 103, "title", "Third Item")
        ))));
        server.stubFor(post("/api/card/12/query/json").willReturn(json(200,
            IntStream.range(0, EXPORT_ROWS).mapToObj(i -> Map.of("id", i)).toList()
        )));
        server.stubFor(post("/api/card/13/query/json").willReturn(json(200, FAILED_QUERY)));

        server.stubFor(post("/api/dataset").willReturn(json(200, completed(
            List.of(col("total")),
            List.of(List.of(42))
        ))));
        server.stubFor(post("/api/dataset").withRequestBody(containing("FAIL_ME")).atPriority(1).willReturn(json(202, FAILED_QUERY)));
        server.stubFor(post("/api/dataset/json").willReturn(json(200, List.of(Map.of("total", 42), Map.of("total", 43)))));

        server.stubFor(get(urlPathMatching("/api/.*"))
            .withHeader("x-api-key", equalTo("bad-key")).atPriority(1)
            .willReturn(json(401, Map.of("message", "Unauthenticated"))));
        server.stubFor(post(urlPathMatching("/api/.*"))
            .withHeader("x-api-key", equalTo("bad-key")).atPriority(1)
            .willReturn(json(401, Map.of("message", "Unauthenticated"))));
    }

    public String getUrl() {
        return server.baseUrl();
    }

    public List<RecordedRequest> getRecordedRequests() {
        var requests = new ArrayList<RecordedRequest>();
        for (var event : server.getAllServeEvents()) {
            var request = event.getRequest();
            var headers = new LinkedHashMap<String, String>();
            request.getHeaders().all().forEach(header -> headers.put(header.key().toLowerCase(), header.firstValue()));
            requests.add(new RecordedRequest(request.getMethod().getName(), request.getUrl(), request.getBodyAsString(), headers));
        }
        // WireMock lists the most recent request first.
        Collections.reverse(requests);
        return requests;
    }

    public void addDashboard(int id, String name, String updatedAt) {
        dashboards.add(dashboard(id, name, updatedAt, updatedAt));
        stubDashboards();
    }

    public void clearDashboards() {
        dashboards.clear();
        stubDashboards();
    }

    private void stubDashboards() {
        server.stubFor(get("/api/dashboard").willReturn(json(200, List.copyOf(dashboards))));
    }

    private static Map<String, Object> dashboard(int id, String name, String createdAt, String updatedAt) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", id);
        map.put("name", name);
        map.put("created_at", createdAt);
        map.put("updated_at", updatedAt);
        return map;
    }

    private static Map<String, Object> col(String name) {
        return Map.of("name", name, "display_name", name);
    }

    private static Map<String, Object> completed(List<Map<String, Object>> cols, List<List<Object>> rows) {
        return Map.of("status", "completed", "data", Map.of("cols", cols, "rows", rows));
    }

    private static Map<String, Object> truncated() {
        return Map.of("status", "completed", "data", Map.of(
            "cols", List.of(col("id")),
            "rows", List.of(List.of(1)),
            "rows_truncated", 2000
        ));
    }

    private static ResponseDefinitionBuilder json(int status, Object body) {
        try {
            return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(JacksonMapper.ofJson().writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop();
    }
}
