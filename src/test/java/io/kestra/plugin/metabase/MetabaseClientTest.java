package io.kestra.plugin.metabase;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
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
        server.close();
    }

    @Test
    void testApiKeyAndSessionTokenAreSentAsHeaders() throws Exception {
        try (var client = client("secret-api-key-123", null, null, null)) {
            client.get(MetabaseApi.DASHBOARDS, MetabaseClient.OBJECT_LIST);
        }
        try (var client = client(null, "session-token-abc", null, null)) {
            client.get(MetabaseApi.DASHBOARDS, MetabaseClient.OBJECT_LIST);
        }

        var requests = server.getRecordedRequests();
        assertThat(requests, hasSize(2));
        assertThat(requests.getFirst().headers().get("x-api-key"), is("secret-api-key-123"));
        assertThat(requests.get(1).headers().get("x-metabase-session"), is("session-token-abc"));
    }

    @Test
    void testCredentialsOpenASessionThatIsDeletedOnClose() throws Exception {
        try (var client = client(null, null, "admin@example.com", "password123")) {
            client.get(MetabaseApi.DASHBOARDS, MetabaseClient.OBJECT_LIST);
        }

        var requests = server.getRecordedRequests();
        assertThat(requests, hasSize(3));
        assertThat(requests.getFirst().path(), is("/api/session"));
        assertThat(requests.get(1).headers().get("x-metabase-session"), is("test-session-token-xyz"));
        assertThat(requests.get(2).method(), is("DELETE"));
        assertThat(requests.get(2).path(), is("/api/session"));
        assertThat(requests.get(2).headers().get("x-metabase-session"), is("test-session-token-xyz"));
    }

    @Test
    void testProvidedSessionTokenIsNotDeletedOnClose() throws Exception {
        client(null, "session-token-abc", null, null).close();

        assertThat(server.getRecordedRequests(), empty());
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

    private MetabaseClient client(String apiKey, String sessionToken, String username, String password) throws Exception {
        var connection = new MetabaseClient.Connection(server.getUrl(), apiKey, sessionToken, username, password, Duration.ofSeconds(10));
        return new MetabaseClient(runContextFactory.of(Map.of()), connection);
    }

    @Test
    void connectionDoesNotPrintSecrets() {
        var connection = new MetabaseClient.Connection("http://metabase", "key-123", "token-456", "user", "pass-789", Duration.ofSeconds(5));

        assertThat(connection.toString(), allOf(not(containsString("key-123")), not(containsString("token-456")), not(containsString("pass-789"))));
    }
}
