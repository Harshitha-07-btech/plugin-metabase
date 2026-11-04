package io.kestra.plugin.metabase;

import io.kestra.core.models.tasks.Output;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Builder
@Getter
public class QueryOutput implements Output {

    @Schema(
        title = "A single row",
        description = """
            Returned when `fetchType` is set to `FETCH_ONE`."""
    )
    private final Map<String, Object> row;

    @Schema(
        title = "List of rows",
        description = """
            Returned when `fetchType` is set to `FETCH`."""
    )
    private final List<Map<String, Object>> rows;

    @Schema(
        title = "Internal storage URI",
        description = """
            Returned when `fetchType` is set to `STORE`. Contains an Amazon Ion-serialized file of the rows."""
    )
    private final URI uri;

    @Schema(
        title = "Number of returned rows",
        description = """
            Total number of rows returned or stored by the query."""
    )
    private final Long size;
}
