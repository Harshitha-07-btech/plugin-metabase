package io.kestra.plugin.metabase.models;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashboardResponse {
    private Integer id;
    private String name;
    private String description;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    private List<Map<String, Object>> dashcards;

    @JsonProperty("ordered_cards")
    private List<Map<String, Object>> orderedCards;

    @JsonAnySetter
    @Builder.Default
    private Map<String, Object> additionalProperties = new LinkedHashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        if (additionalProperties != null) {
            map.putAll(additionalProperties);
        }
        if (id != null) {
            map.put("id", id);
        }
        if (name != null) {
            map.put("name", name);
        }
        if (description != null) {
            map.put("description", description);
        }
        if (createdAt != null) {
            map.put("created_at", createdAt);
        }
        if (updatedAt != null) {
            map.put("updated_at", updatedAt);
        }
        if (dashcards != null) {
            map.put("dashcards", dashcards);
        }
        if (orderedCards != null) {
            map.put("ordered_cards", orderedCards);
        }
        return map;
    }
}
