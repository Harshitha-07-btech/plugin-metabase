package io.kestra.plugin.metabase;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;

import java.time.Duration;

// Shared by the tasks and the trigger, which cannot share a superclass.
public interface MetabaseConnectionInterface {
    Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    Property<String> getUrl();

    Property<String> getApiKey();

    Property<String> getSessionToken();

    Property<String> getUsername();

    Property<String> getPassword();

    Property<Duration> getRequestTimeout();

    default MetabaseClient createClient(RunContext runContext) throws Exception {
        var rUrl = runContext.render(this.getUrl()).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("Property 'url' is required, set the base URL of your Metabase instance."));

        return new MetabaseClient(runContext, new MetabaseClient.Connection(
            rUrl,
            runContext.render(this.getApiKey()).as(String.class).orElse(null),
            runContext.render(this.getSessionToken()).as(String.class).orElse(null),
            runContext.render(this.getUsername()).as(String.class).orElse(null),
            runContext.render(this.getPassword()).as(String.class).orElse(null),
            runContext.render(this.getRequestTimeout()).as(Duration.class).orElse(DEFAULT_REQUEST_TIMEOUT)
        ));
    }
}
