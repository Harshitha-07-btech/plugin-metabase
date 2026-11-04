package io.kestra.plugin.metabase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MetabaseClient implements Closeable {
    public static final TypeReference<List<Map<String, Object>>> OBJECT_LIST = new TypeReference<>() {};
    public static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();
    private static final int MAX_ERROR_DETAIL_LENGTH = 500;
    private static final String API_KEY_HEADER = "x-api-key";
    private static final String SESSION_HEADER = "X-Metabase-Session";

    private enum AuthMode {
        API_KEY,
        SESSION_TOKEN,
        CREDENTIALS
    }

    public record Connection(String url, String apiKey, String sessionToken, String username, String password, Duration requestTimeout) {
        AuthMode authMode() {
            var hasUsername = StringUtils.isNotBlank(username);
            if (hasUsername != StringUtils.isNotBlank(password)) {
                throw new IllegalArgumentException("Properties 'username' and 'password' must be set together.");
            }

            var modes = new ArrayList<AuthMode>();
            if (StringUtils.isNotBlank(apiKey)) {
                modes.add(AuthMode.API_KEY);
            }
            if (StringUtils.isNotBlank(sessionToken)) {
                modes.add(AuthMode.SESSION_TOKEN);
            }
            if (hasUsername) {
                modes.add(AuthMode.CREDENTIALS);
            }
            if (modes.size() != 1) {
                throw new IllegalArgumentException(
                    "Exactly one authentication mode is required: set 'apiKey', 'sessionToken', or both 'username' and 'password'. Found %d.".formatted(modes.size())
                );
            }
            return modes.getFirst();
        }

        @Override
        public String toString() {
            return "Connection[url=%s, requestTimeout=%s]".formatted(url, requestTimeout);
        }
    }

    private final RunContext runContext;
    private final String baseUrl;
    private final AuthMode authMode;
    private final String apiKey;
    private final HttpClient httpClient;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private String sessionToken;

    // A session opened from username and password is deleted on close().
    public MetabaseClient(RunContext runContext, Connection connection) throws Exception {
        this.authMode = connection.authMode();
        this.runContext = runContext;
        this.baseUrl = StringUtils.stripEnd(connection.url(), "/");
        this.apiKey = connection.apiKey();
        this.sessionToken = connection.sessionToken();

        this.httpClient = new HttpClient(runContext, HttpConfiguration.builder()
            .timeout(TimeoutConfiguration.builder()
                .connectTimeout(Property.ofValue(connection.requestTimeout()))
                .readIdleTimeout(Property.ofValue(connection.requestTimeout()))
                .build())
            .build());

        if (authMode == AuthMode.CREDENTIALS) {
            try {
                openSession(connection.username(), connection.password());
            } catch (Exception e) {
                this.httpClient.close();
                throw e;
            }
        }
    }

    public <T> T get(String path, TypeReference<T> type) throws Exception {
        return parse(send(request("GET", path).build()), MAPPER.getTypeFactory().constructType(type), path);
    }

    public <T> T post(String path, Object body, Class<T> type) throws Exception {
        var request = request("POST", path).body(HttpRequest.JsonRequestBody.of(body != null ? body : Map.of())).build();
        return parse(send(request), MAPPER.getTypeFactory().constructType(type), path);
    }

    @FunctionalInterface
    public interface StreamHandler {
        void accept(InputStream stream) throws IOException;
    }

    public void postFormStream(String path, Map<String, Object> form, StreamHandler handler) throws Exception {
        var request = request("POST", path).body(HttpRequest.UrlEncodedRequestBody.of(form)).build();

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

    private void openSession(String username, String password) throws Exception {
        var session = post(MetabaseApi.SESSION, Map.of("username", username, "password", password), SessionResponse.class);
        if (StringUtils.isBlank(session.getId())) {
            throw new IllegalStateException("Metabase did not return a session id for the given username and password, check the credentials.");
        }
        this.sessionToken = session.getId();
    }

    private String send(HttpRequest request) throws Exception {
        try {
            return httpClient.request(request, String.class).getBody();
        } catch (HttpClientResponseException e) {
            throw translate(e, request.getUri().getPath());
        }
    }

    private HttpRequest.HttpRequestBuilder request(String method, String path) {
        var builder = HttpRequest.builder()
            .uri(URI.create("%s%s".formatted(this.baseUrl, path)))
            .method(method)
            .addHeader("Accept", "application/json");

        if (authMode == AuthMode.API_KEY) {
            builder.addHeader(API_KEY_HEADER, this.apiKey);
        } else if (sessionToken != null) {
            builder.addHeader(SESSION_HEADER, this.sessionToken);
        }
        return builder;
    }

    private static <T> T parse(String body, JavaType type, String path) throws IOException {
        if (StringUtils.isBlank(body)) {
            throw new IllegalStateException("Received an empty response from the Metabase API for %s. Check the Metabase server logs.".formatted(path));
        }
        return MAPPER.readValue(body, type);
    }

    private static IllegalStateException translate(HttpClientResponseException e, String path) {
        var status = e.getResponse().getStatus().getCode();
        var body = e.getResponse().getBody();
        var text = body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(body);
        var detail = body == null ? "" : StringUtils.abbreviate(text.strip(), MAX_ERROR_DETAIL_LENGTH);
        var suffix = detail.isEmpty() ? "" : ": %s".formatted(detail);

        var message = switch (status) {
            case 401, 403 -> "Metabase rejected the request to %s with status %d. Check the credentials and that they have permission on this resource%s".formatted(path, status, suffix);
            case 404 -> "Metabase returned 404 for %s. Check that the URL and the card, database or dashboard id exist%s".formatted(path, suffix);
            default -> "Metabase request to %s failed with status %d. Check the Metabase server logs and the request properties%s".formatted(path, status, suffix);
        };
        return new IllegalStateException(message, e);
    }

    // Closes the connections at once without deleting the session, so it never blocks. Never throws.
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
            if (authMode == AuthMode.CREDENTIALS && StringUtils.isNotBlank(this.sessionToken)) {
                deleteSession();
            }
        } finally {
            this.httpClient.close();
        }
    }

    private void deleteSession() {
        try {
            send(request("DELETE", MetabaseApi.SESSION).build());
        } catch (Exception e) {
            runContext.logger().warn("Could not delete the Metabase session, it will expire on its own: {}", e.getMessage());
        }
    }
}
