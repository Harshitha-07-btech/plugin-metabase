package io.kestra.plugin.metabase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.kestra.core.serializers.JacksonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

public class MetabaseMockServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    public static final int EXPORT_ROWS = 5000;
    private static final Map<String, Object> FAILED_QUERY = Map.of(
        "status", "failed",
        "error", "ERROR: relation \"missing_table\" does not exist",
        "row_count", 0
    );

    private final HttpServer server;
    private final List<Map<String, Object>> dashboards = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> recordedRequests = new CopyOnWriteArrayList<>();

    public record RecordedRequest(String method, String path, Map<String, List<String>> headers, String body) {}

    public MetabaseMockServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // Seed initial dashboards
        Map<String, Object> d1 = new LinkedHashMap<>();
        d1.put("id", 1);
        d1.put("name", "Executive Overview");
        d1.put("description", "Weekly KPI dashboard");
        d1.put("created_at", "2026-10-01T10:00:00Z");
        d1.put("updated_at", "2026-10-15T12:00:00Z");

        Map<String, Object> d2 = new LinkedHashMap<>();
        d2.put("id", 2);
        d2.put("name", "Operations");
        d2.put("description", "Daily ops metrics");
        d2.put("created_at", "2026-10-05T08:00:00Z");
        d2.put("updated_at", "2026-10-20T14:30:00Z");

        dashboards.add(d1);
        dashboards.add(d2);

        server.createContext("/", new MainHandler());
        server.setExecutor(null);
        server.start();
    }

    public String getUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<RecordedRequest> getRecordedRequests() {
        return recordedRequests;
    }

    public void addDashboard(int id, String name, String updatedAt) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("name", name);
        d.put("description", "New dashboard");
        d.put("created_at", updatedAt);
        d.put("updated_at", updatedAt);
        dashboards.add(d);
    }

    private class MainHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            recordedRequests.add(new RecordedRequest(method, path, exchange.getRequestHeaders(), body));

            if ("bad-key".equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
                sendJson(exchange, 401, Map.of("message", "Unauthenticated"));
                return;
            }

            if ("DELETE".equalsIgnoreCase(method) && "/api/session".equals(path)) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/card/11/query".equals(path)) {
                sendJson(exchange, 202, FAILED_QUERY);
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/dataset".equals(path) && body.contains("FAIL_ME")) {
                sendJson(exchange, 202, FAILED_QUERY);
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/card/10/query/json".equals(path)) {
                sendJson(exchange, 200, List.of(
                    Map.of("id", 101, "title", "First Item"),
                    Map.of("id", 102, "title", "Second Item"),
                    Map.of("id", 103, "title", "Third Item")
                ));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/card/12/query/json".equals(path)) {
                sendJson(exchange, 200, IntStream.range(0, EXPORT_ROWS).mapToObj(i -> Map.of("id", i)).toList());
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/dataset/json".equals(path)) {
                sendJson(exchange, 200, List.of(Map.of("total", 42), Map.of("total", 43)));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/session".equals(path)) {
                sendJson(exchange, 200, Map.of("id", "test-session-token-xyz"));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/database/1/sync_schema".equals(path)) {
                sendJson(exchange, 200, Map.of("success", true));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/database/1/rescan_values".equals(path)) {
                sendJson(exchange, 200, Map.of("success", true));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/card/10/query".equals(path)) {
                Map<String, Object> result = Map.of(
                    "data", Map.of(
                        "cols", List.of(
                            Map.of("name", "id", "display_name", "ID", "base_type", "type/Integer"),
                            Map.of("name", "title", "display_name", "Title", "base_type", "type/Text")
                        ),
                        "rows", List.of(
                            List.of(101, "First Item"),
                            List.of(102, "Second Item")
                        )
                    ),
                    "row_count", 2,
                    "status", "completed"
                );
                sendJson(exchange, 200, result);
                return;
            }

            if ("POST".equalsIgnoreCase(method) && "/api/dataset".equals(path)) {
                Map<String, Object> result = Map.of(
                    "data", Map.of(
                        "cols", List.of(
                            Map.of("name", "total", "display_name", "Total Count", "base_type", "type/Integer")
                        ),
                        "rows", List.of(
                            List.of(42)
                        )
                    ),
                    "row_count", 1,
                    "status", "completed"
                );
                sendJson(exchange, 200, result);
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/dashboard".equals(path)) {
                sendJson(exchange, 200, dashboards);
                return;
            }

            if ("GET".equalsIgnoreCase(method) && "/api/dashboard/1".equals(path)) {
                Map<String, Object> dashboardWithCards = new LinkedHashMap<>();
                dashboardWithCards.put("id", 1);
                dashboardWithCards.put("name", "Executive Overview");
                dashboardWithCards.put("description", "Weekly KPI dashboard");
                dashboardWithCards.put("dashcards", List.of(
                    Map.of("id", 11, "card_id", 10, "row", 0, "col", 0),
                    Map.of("id", 12, "card_id", 20, "row", 1, "col", 0)
                ));
                sendJson(exchange, 200, dashboardWithCards);
                return;
            }

            sendJson(exchange, 404, Map.of("error", "Not Found: " + path));
        }

        private void sendJson(HttpExchange exchange, int statusCode, Object data) throws IOException {
            byte[] bytes = MAPPER.writeValueAsBytes(data);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
