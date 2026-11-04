package io.kestra.plugin.metabase;

import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.metabase.models.QueryResponse;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class QueryOutputHelper {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final String COMPLETED_STATUS = "completed";
    private static final String NO_ERROR_MESSAGE = "Metabase gave no error message";
    private static final TypeReference<LinkedHashMap<String, Object>> ROW_TYPE = new TypeReference<>() {};

    private QueryOutputHelper() {
    }

    public static QueryOutput fetch(
        RunContext runContext,
        MetabaseClient client,
        FetchType fetchType,
        String path,
        Object body,
        Map<String, Object> exportForm
    ) throws Exception {
        if (fetchType == FetchType.STORE) {
            return store(runContext, client, "%s%s".formatted(path, MetabaseApi.EXPORT_SUFFIX), exportForm);
        }

        var rows = parseRows(client.post(path, body, QueryResponse.class), fetchType);
        runContext.logger().debug("Extracted {} rows from {}", rows.size(), path);
        return processOutput(rows, fetchType);
    }

    static List<Map<String, Object>> parseRows(QueryResponse queryResult, FetchType fetchType) {
        // A failed query still answers HTTP 202, the failure is only visible in the body.
        if (!COMPLETED_STATUS.equals(queryResult.getStatus())) {
            var detail = queryResult.getError() != null ? queryResult.getError() : NO_ERROR_MESSAGE;
            throw new IllegalStateException(
                "Metabase query ended with status '%s': %s. Fix the query or its parameters and run the task again.".formatted(queryResult.getStatus(), detail)
            );
        }

        if (queryResult.getData() == null) {
            return Collections.emptyList();
        }
        // Metabase caps inline results and only flags it, so every fetch type but FETCH_ONE would silently return a partial result.
        if (fetchType != FetchType.FETCH_ONE && queryResult.getData().getRowsTruncated() != null) {
            throw new IllegalStateException(
                "Metabase truncated the result to %d rows. Use `fetchType: STORE` to get every row.".formatted(queryResult.getData().getRowsTruncated())
            );
        }
        return parseData(queryResult.getData().getCols(), queryResult.getData().getRows());
    }

    private static List<Map<String, Object>> parseData(List<Map<String, Object>> cols, List<List<Object>> rows) {
        var records = new ArrayList<Map<String, Object>>();
        if (cols == null || rows == null) {
            return records;
        }

        var colNames = new ArrayList<String>();
        for (var i = 0; i < cols.size(); i++) {
            var col = cols.get(i);
            var name = col.get("name") != null ? col.get("name") : col.get("display_name");
            colNames.add(name != null ? name.toString() : "col_%d".formatted(i));
        }

        for (var row : rows) {
            var map = new LinkedHashMap<String, Object>();
            for (var i = 0; i < colNames.size() && i < row.size(); i++) {
                map.put(colNames.get(i), row.get(i));
            }
            records.add(map);
        }

        return records;
    }

    private static QueryOutput processOutput(List<Map<String, Object>> rows, FetchType fetchType) {
        return switch (fetchType) {
            case FETCH_ONE -> {
                var singleRow = rows.isEmpty() ? null : rows.getFirst();
                yield QueryOutput.builder().row(singleRow).size(singleRow == null ? 0L : 1L).build();
            }
            case FETCH -> QueryOutput.builder().rows(rows).size((long) rows.size()).build();
            case NONE -> QueryOutput.builder().size((long) rows.size()).build();
            default -> throw new IllegalArgumentException("Unsupported fetch type for inline results: %s".formatted(fetchType));
        };
    }

    private static QueryOutput store(RunContext runContext, MetabaseClient client, String path, Map<String, Object> form) throws Exception {
        var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
        var rowCount = new long[1];

        var exportForm = new HashMap<String, Object>(form);
        // Raw values, not the display formatting Metabase applies by default.
        exportForm.put("format_rows", false);
        try (var outputStream = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
            client.postFormStream(path, exportForm, stream -> rowCount[0] = writeRows(stream, outputStream, path));
        }

        return QueryOutput.builder()
            .uri(runContext.storage().putFile(tempFile))
            .size(rowCount[0])
            .build();
    }

    private static long writeRows(InputStream stream, OutputStream outputStream, String path) throws IOException {
        if (stream == null) {
            throw new IllegalStateException("Received an empty response from the Metabase API for %s. Check the Metabase server logs.".formatted(path));
        }

        try (var parser = MAPPER.getFactory().createParser(stream)) {
            var first = parser.nextToken();

            // Metabase answers an export that failed after start with a single error object instead of the row array.
            if (first == JsonToken.START_OBJECT) {
                var node = MAPPER.readValue(parser, JsonNode.class);
                var error = node.path("error").asText(NO_ERROR_MESSAGE);
                throw new IllegalStateException("Metabase export failed: %s. Fix the query or its parameters and run the task again.".formatted(error));
            }
            if (first != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Unexpected response from the Metabase export endpoint %s, expected a JSON array of rows.".formatted(path));
            }

            var count = 0L;
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                FileSerde.write(outputStream, MAPPER.readValue(parser, ROW_TYPE));
                count++;
            }
            return count;
        }
    }
}
