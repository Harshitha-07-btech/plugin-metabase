package io.kestra.plugin.metabase;

public final class MetabaseApi {
    public static final String SESSION = "/api/session";
    public static final String DASHBOARDS = "/api/dashboard";
    public static final String DASHBOARD = "/api/dashboard/%d";
    public static final String DATASET = "/api/dataset";
    public static final String CARD_QUERY = "/api/card/%d/query";
    public static final String DATABASE_ACTION = "/api/database/%d/%s";
    // Metabase serves the uncapped export of a query endpoint at the same path with this suffix.
    public static final String EXPORT_SUFFIX = "/json";

    private MetabaseApi() {
    }
}
