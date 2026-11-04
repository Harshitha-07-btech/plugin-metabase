package io.kestra.plugin.metabase;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class MetabaseClientTest {

    @Inject
    private RunContextFactory runContextFactory;

    private MetabaseMockServer server;

    @BeforeEach
    void setUp() {
        server = new MetabaseMockServer();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void testApiKeyAuth() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        try (MetabaseClient client = new MetabaseClient(
            runContext,
            server.getUrl(),
            "secret-api-key-123",
            null,
            null,
            null,
            Duration.ofSeconds(10)
        )) {
            var response = client.get("/api/dashboard");
            assertThat(response, notNullValue());

            var requests = server.getRecordedRequests();
            assertThat(requests, hasSize(1));
            assertThat(requests.getFirst().headers().get("x-api-key"), is("secret-api-key-123"));
        }
    }

    @Test
    void testSessionTokenAuth() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        try (MetabaseClient client = new MetabaseClient(
            runContext,
            server.getUrl(),
            null,
            "session-token-abc",
            null,
            null,
            Duration.ofSeconds(10)
        )) {
            var response = client.get("/api/dashboard");
            assertThat(response, notNullValue());

            var requests = server.getRecordedRequests();
            assertThat(requests, hasSize(1));
            assertThat(requests.getFirst().headers().get("x-metabase-session"), is("session-token-abc"));
        }
    }

    @Test
    void testUsernamePasswordAuthFallback() throws Exception {
        var runContext = runContextFactory.of(Map.of());

        try (MetabaseClient client = new MetabaseClient(
            runContext,
            server.getUrl(),
            null,
            null,
            "admin@example.com",
            "password123",
            Duration.ofSeconds(10)
        )) {
            var response = client.get("/api/dashboard");
            assertThat(response, notNullValue());

            var requests = server.getRecordedRequests();
            // First call was POST /api/session, second call was GET /api/dashboard
            assertThat(requests, hasSize(2));
            assertThat(requests.getFirst().path(), is("/api/session"));
            assertThat(requests.get(1).path(), is("/api/dashboard"));
            assertThat(requests.get(1).headers().get("x-metabase-session"), is("test-session-token-xyz"));
        }
    }

    @Test
    void testNoAuthenticationModeIsRejected() {
        var e = assertThrows(IllegalArgumentException.class, () -> client(null, null, null, null));
        assertThat(e.getMessage(), allOf(containsString("apiKey"), containsString("sessionToken"), containsString("username")));
    }

    @Test
    void testAmbiguousAuthenticationIsRejected() {
        var e = assertThrows(IllegalArgumentException.class, () -> client("key", "token", null, null));
        assertThat(e.getMessage(), containsString("Exactly one authentication mode"));
        assertThat(server.getRecordedRequests(), empty());
    }

    @Test
    void testUsernameWithoutPasswordIsRejected() {
        var e = assertThrows(IllegalArgumentException.class, () -> client(null, null, "admin@example.com", null));
        assertThat(e.getMessage(), allOf(containsString("username"), containsString("password")));
    }

    @Test
    void testSessionCreatedFromCredentialsIsDeletedOnClose() throws Exception {
        client(null, null, "admin@example.com", "password123").close();

        var requests = server.getRecordedRequests();
        assertThat(requests, hasSize(2));
        assertThat(requests.get(1).method(), is("DELETE"));
        assertThat(requests.get(1).path(), is("/api/session"));
        assertThat(requests.get(1).headers().get("x-metabase-session"), is("test-session-token-xyz"));
    }

    @Test
    void testProvidedSessionTokenIsNotDeletedOnClose() throws Exception {
        client(null, "session-token-abc", null, null).close();

        assertThat(server.getRecordedRequests(), empty());
    }

    @Test
    void testUnauthorizedAndNotFoundAreTranslated() throws Exception {
        try (var unauthorized = client("bad-key", null, null, null)) {
            var e = assertThrows(IllegalStateException.class, () -> unauthorized.get("/api/dashboard"));
            assertThat(e.getMessage(), allOf(containsString("401"), containsString("credentials")));
        }
        try (var client = client("key", null, null, null)) {
            var e = assertThrows(IllegalStateException.class, () -> client.get("/api/unknown"));
            assertThat(e.getMessage(), containsString("404"));
        }
    }

    private MetabaseClient client(String apiKey, String sessionToken, String username, String password) throws Exception {
        return new MetabaseClient(runContextFactory.of(Map.of()), server.getUrl(), apiKey, sessionToken, username, password, Duration.ofSeconds(10));
    }
}
