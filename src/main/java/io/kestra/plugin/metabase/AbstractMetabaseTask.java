package io.kestra.plugin.metabase;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.kestra.core.exceptions.KilledException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMetabaseTask extends Task implements MetabaseConnectionInterface {

    @Schema(
        title = "Metabase server URL",
        description = """
            Base URL of the Metabase instance, for example `https://metabase.example.com` or `http://localhost:3000`."""
    )
    @PluginProperty(group = "connection")
    @NotNull
    protected Property<String> url;

    @Schema(
        title = "Metabase API key",
        description = """
            API key sent in the `x-api-key` header. Requires Metabase v0.47 or later.
            Set exactly one authentication mode: `apiKey`, `sessionToken`, or both `username` and `password`."""
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> apiKey;

    @Schema(
        title = "Metabase session token",
        description = """
            Session token sent in the `X-Metabase-Session` header.
            Set exactly one authentication mode: `apiKey`, `sessionToken`, or both `username` and `password`."""
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> sessionToken;

    @Schema(
        title = "Metabase username",
        description = """
            Username or email used to open a session through `POST /api/session`. Must be set together with `password`.
            The session is closed with `DELETE /api/session` when the task ends."""
    )
    @PluginProperty(group = "connection")
    protected Property<String> username;

    @Schema(
        title = "Metabase password",
        description = """
            Password used to open a session through `POST /api/session`. Must be set together with `username`."""
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> password;

    @Schema(
        title = "HTTP request timeout",
        description = """
            Connect and read timeout for each HTTP request made to Metabase. Defaults to 60 seconds. Does not limit the task duration."""
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<Duration> requestTimeout = Property.ofValue(DEFAULT_REQUEST_TIMEOUT);

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Builder.Default
    private final AtomicReference<MetabaseClient> killableClient = new AtomicReference<>();

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Builder.Default
    private final AtomicBoolean isKilled = new AtomicBoolean(false);

    @Override
    public MetabaseClient createClient(RunContext runContext) throws Exception {
        var client = MetabaseConnectionInterface.super.createClient(runContext);
        killableClient.set(client);
        // A kill that arrived while the client was being created found nothing to abort.
        if (isKilled.get()) {
            client.close();
            throw new KilledException("The task was killed before its Metabase request started.");
        }
        return client;
    }

    // Aborting the client stops the in-flight request without waiting on Metabase.
    public void kill() {
        if (isKilled.compareAndSet(false, true)) {
            Optional.ofNullable(killableClient.get()).ifPresent(MetabaseClient::abort);
        }
    }
}
