package io.kestra.plugin.metabase.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryResponse {
    private QueryData data;
    private String status;
    private String error;

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryData {
        private List<Map<String, Object>> cols;
        private List<List<Object>> rows;

        @JsonProperty("rows_truncated")
        private Long rowsTruncated;
    }
}
