package io.kestra.plugin.metabase;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.metabase.models.QueryResponse;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

class QueryOutputHelperTest {

    @Test
    void mapsRowsToColumnNames() throws Exception {
        var response = JacksonMapper.ofJson().readValue("""
            {
                "status": "completed",
                "data": {
                    "cols": [
                        { "name": "id", "display_name": "ID" },
                        { "display_name": "Product Category" }
                    ],
                    "rows": [ [ 101, "Doohickey" ], [ 102, "Widget" ] ]
                }
            }
            """, QueryResponse.class);

        var rows = QueryOutputHelper.parseRows(response, FetchType.FETCH);

        assertThat(rows, hasSize(2));
        assertThat(rows.getFirst().get("id"), is(101));
        assertThat(rows.get(1).get("Product Category"), is("Widget"));
    }
}
