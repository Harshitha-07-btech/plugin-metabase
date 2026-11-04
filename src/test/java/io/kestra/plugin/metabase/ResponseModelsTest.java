package io.kestra.plugin.metabase;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.metabase.models.DashboardResponse;
import io.kestra.plugin.metabase.models.DatabaseActionResponse;
import io.kestra.plugin.metabase.models.QueryResponse;
import io.kestra.plugin.metabase.models.SessionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponseModelsTest {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Test
    void testSessionResponseWithUnknownFields() throws Exception {
        String json = """
            {
                "id": "session-xyz-123",
                "user_id": 42,
                "extra_unexpected_field": "ignore_me"
            }
            """;
        SessionResponse response = MAPPER.readValue(json, SessionResponse.class);
        assertThat(response, notNullValue());
        assertThat(response.getId(), is("session-xyz-123"));
    }

    @Test
    void testDatabaseActionResponseWithUnknownFields() throws Exception {
        String json = """
            {
                "success": true,
                "status": "ok",
                "task_history_id": 12345,
                "extra_metadata": { "duration_ms": 42 }
            }
            """;
        DatabaseActionResponse response = MAPPER.readValue(json, DatabaseActionResponse.class);
        assertThat(response, notNullValue());
        assertThat(response.getSuccess(), is(true));
        assertThat(response.getStatus(), is("ok"));
    }

    @Test
    void testDashboardResponseWithUnknownFields() throws Exception {
        String json = """
            {
                "id": 1,
                "name": "Executive Dashboard",
                "description": "Weekly metrics",
                "created_at": "2026-10-01T10:00:00Z",
                "updated_at": "2026-10-15T12:00:00Z",
                "collection_id": 42,
                "archived": false,
                "parameters": [{ "id": "p1", "name": "Date" }],
                "dashcards": [
                    { "id": 101, "card_id": 10 }
                ]
            }
            """;
        DashboardResponse response = MAPPER.readValue(json, DashboardResponse.class);
        assertThat(response, notNullValue());
        assertThat(response.getId(), is(1));
        assertThat(response.getName(), is("Executive Dashboard"));
        assertThat(response.getCreatedAt(), is("2026-10-01T10:00:00Z"));
        assertThat(response.getUpdatedAt(), is("2026-10-15T12:00:00Z"));
        assertThat(response.getDashcards(), hasSize(1));

        Map<String, Object> map = response.toMap();
        assertThat(map.get("id"), is(1));
        assertThat(map.get("name"), is("Executive Dashboard"));
        assertThat(map.get("collection_id"), is(42));
        assertThat(map.get("archived"), is(false));
    }

    @Test
    void testQueryResponseWithDynamicColumnsAndRows() throws Exception {
        String json = """
            {
                "status": "completed",
                "row_count": 2,
                "database_id": 1,
                "future_api_field": "should_be_ignored",
                "data": {
                    "cols": [
                        { "name": "id", "display_name": "ID", "base_type": "type/Integer" },
                        { "name": "category", "display_name": "Product Category", "base_type": "type/Text" }
                    ],
                    "rows": [
                        [ 101, "Doohickey" ],
                        [ 102, "Widget" ]
                    ]
                }
            }
            """;
        QueryResponse response = MAPPER.readValue(json, QueryResponse.class);
        assertThat(response, notNullValue());
        assertThat(response.getStatus(), is("completed"));
        assertThat(response.getRowCount(), is(2L));
        assertThat(response.getData(), notNullValue());

        List<Map<String, Object>> parsed = QueryOutputHelper.parseRows(response);
        assertThat(parsed, hasSize(2));
        assertThat(parsed.get(0).get("id"), is(101));
        assertThat(parsed.get(0).get("category"), is("Doohickey"));
        assertThat(parsed.get(1).get("id"), is(102));
        assertThat(parsed.get(1).get("category"), is("Widget"));
    }

    @Test
    void testQueryResponseWithNullData() {
        List<Map<String, Object>> nullResponse = QueryOutputHelper.parseRows((QueryResponse) null);
        assertThat(nullResponse, empty());

        QueryResponse emptyData = QueryResponse.builder().status("completed").build();
        List<Map<String, Object>> emptyDataResponse = QueryOutputHelper.parseRows(emptyData);
        assertThat(emptyDataResponse, empty());
    }

    @Test
    void testFailedQueryResponseThrowsWithMetabaseError() throws Exception {
        var response = MAPPER.readValue("""
            { "status": "failed", "error": "ERROR: syntax error at position 7", "row_count": 0 }
            """, QueryResponse.class);

        var e = assertThrows(IllegalStateException.class, () -> QueryOutputHelper.parseRows(response));
        assertThat(e.getMessage(), containsString("ERROR: syntax error at position 7"));
    }
}
