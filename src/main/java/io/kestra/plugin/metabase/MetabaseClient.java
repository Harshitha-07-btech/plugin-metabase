package io.kestra.plugin.metabase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.metabase.models.SessionResponse;
import org.apache.commons.lang3.StringUtils;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MetabaseClient implements Closeable {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final int MAX_ERROR_DETAIL_LENGTH = 500;

    private final RunContext runContext;
    private final String baseUrl;
    private final String apiKey;
    private final boolean isSessionOwned;
    private final HttpClient httpClient;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private String sessionToken;

    // A session opened from username and password is deleted on close().
    public MetabaseClient(
        RunContext runContext,
        String baseUrl,
        String apiKey,
        String sessionToken,
        String username,
        String password,
        Duration timeout
    ) throws Exception {
        validateAuthentication(apiKey, sessionToken, username, password);

        this.runContext = runContext;
        this.baseUrl = StringUtils.stripEnd(baseUrl, "/");
        this.apiKey = apiKey;
        this.sessionToken = sessionToken;
        this.isSessionOwned = StringUtils.isNotBlank(username);

        var configBuilder = HttpConfiguration.builder();
        if (timeout != null) {
            configBuilder.timeout(TimeoutConfiguration.builder()
                .connectTimeout(Property.ofValue(timeout))
                .readIdleTimeout(Property.ofValue(timeout))
                .build());
        }
        this.httpClient = new HttpClient(runContext, configBuilder.build());

        if (this.isSessionOwned) {
            try {
                openSession(username, password);
            } catch (Exception e) {
                this.httpClient.close();
                throw e;
            }
        }
    }

    private static void validateAuthentication(String apiKey, String sessionToken, String username, String password) {
        var hasUsername = StringUtils.isNotBlank(username);
        var hasPassword = StringUtils.isNotBlank(password);
        if (hasUsername != hasPassword) {
            throw new IllegalArgumentException("Properties 'username' and 'password' must be set together.");
        }

        var modes = (StringUtils.isNotBlank(apiKey) ? 1 : 0) + (StringUtils.isNotBlank(sessionToken) ? 1 : 0) + (hasUsername ? 1 : 0);
        if (modes != 1) {
            throw new IllegalArgumentException(
                "Exactly one authentication mode is required: set 'apiKey', 'sessionToken', or both 'username' and 'password'. Found %d.".formatted(modes)
            );
        }
    }

    private void openSession(String username, String password) throws Exception {
        var request = HttpRequest.builder()
            .uri(buildUri("/api/session"))
            .method("POST")
            .body(HttpRequest.JsonRequestBody.of(Map.of("username", username, "password", password)))
            .build();

        var session = parse(send(request).getBody(), SessionResponse.class, "/api/session");
        if (StringUtils.isBlank(session.getId())) {
            throw new IllegalStateException("Metabase did not return a session id for the given username and password, check the credentials.");
        }
        this.sessionToken = session.getId();
        runContext.logger().debug("Opened a Metabase session.");
    }

    public String get(String path) throws Exception {
        return send(baseRequestBuilder(path, "GET").build()).getBody();
    }

    public List<Map<String, Object>> getList(String path) throws Exception {
        return MAPPER.readValue(requireBody(get(path), path), new TypeReference<>() {});
    }

    public Map<String, Object> getMap(String path) throws Exception {
        return MAPPER.readValue(requireBody(get(path), path), new TypeReference<>() {});
    }

    public String post(String path, Object body) throws Exception {
        var request = baseRequestBuilder(path, "POST")
            .body(HttpRequest.JsonRequestBody.of(body != null ? body : Map.of()))
            .build();
        return send(request).getBody();
    }

    public <T> T post(String path, Object body, Class<T> clazz) throws Exception {
        return parse(post(path, body), clazz, path);
    }

    @FunctionalInterface
    public interface StreamHandler {
        void accept(InputStream stream) throws IOException;
    }

    public void postFormStream(String path, Map<String, Object> form, StreamHandler handler) throws Exception {
        var request = baseRequestBuilder(path, "POST")
            .body(HttpRequest.UrlEncodedRequestBody.of(form))
            .build();

        try {
            httpClient.request(request, response ->
            {
                try {
                    handler.accept(response.getBody());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (HttpClientResponseException e) {
            throw translate(e, path);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        try {
            return httpClient.request(request, String.class);
        } catch (HttpClientResponseException e) {
            throw translate(e, request.getUri().getPath());
        }
    }

    private static IllegalStateException translate(HttpClientResponseException e, String path) {
        var status = e.getResponse().getStatus().getCode();
        var detail = errorDetail(e.getResponse().getBody());
        var suffix = detail.isEmpty() ? "" : ": %s".formatted(detail);

        var message = switch (status) {
            case 401, 403 -> "Metabase rejected the request to %s with status %d. Check the credentials and that they have permission on this resource%s".formatted(path, status, suffix);
            case 404 -> "Metabase returned 404 for %s. Check that the URL and the card, database or dashboard id exist%s".formatted(path, suffix);
            default -> "Metabase request to %s failed with status %d. Check the Metabase server logs and the request properties%s".formatted(path, status, suffix);
        };
        return new IllegalStateException(message, e);
    }

    private static String errorDetail(Object body) {
        if (body == null) {
            return "";
        }
        var text = (body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : body.toString()).strip();
        return StringUtils.abbreviate(text, MAX_ERROR_DETAIL_LENGTH);
    }

    private static <T> T parse(String body, Class<T> clazz, String path) throws IOException {
        return MAPPER.readValue(requireBody(body, path), clazz);
    }

    private static String requireBody(String body, String path) {
        if (StringUtils.isBlank(body)) {
            throw new IllegalStateException("Received an empty response from the Metabase API for %s. Check the Metabase server logs.".formatted(path));
        }
        return body;
    }

    private URI buildUri(String path) {
        return URI.create(this.baseUrl + path);
    }

    private HttpRequest.HttpRequestBuilder baseRequestBuilder(String path, String method) {
        var builder = HttpRequest.builder()
            .uri(buildUri(path))
            .method(method)
            .addHeader("Accept", "application/json");

        if (StringUtils.isNotBlank(this.apiKey)) {
            builder.addHeader("x-api-key", this.apiKey);
        } else {
            builder.addHeader("X-Metabase-Session", this.sessionToken);
        }

        return builder;
    }

    /** Closes the connections at once without deleting the session, so it never blocks. Never throws. */
    public void abort() {
        try {
            this.httpClient.close();
        } catch (Exception e) {
            runContext.logger().warn("Could not abort the Metabase request: {}", e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            if (this.isSessionOwned && StringUtils.isNotBlank(this.sessionToken)) {
                deleteSession();
            }
        } finally {
            this.httpClient.close();
        }
    }

    private void deleteSession() {
        try {
            send(baseRequestBuilder("/api/session", "DELETE").build());
        } catch (Exception e) {
            runContext.logger().warn("Could not delete the Metabase session, it will expire on its own: {}", e.getMessage());
        }
    }
}
