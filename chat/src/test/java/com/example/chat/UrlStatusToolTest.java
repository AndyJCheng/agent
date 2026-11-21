package com.example.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbacks;

class UrlStatusToolTest {
    private final HttpClient client = mock(HttpClient.class);
    private final UrlStatusTool tool = new UrlStatusTool(client, "example.com");

    @Test
    void toolCallbackCanReturnResultToModel() throws Exception {
        var callbacks = ToolCallbacks.from(tool);
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("checkUrlStatus");

        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(204);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThat(callbacks[0].call("{\"url\":\"https://example.com/\"}"))
                .contains("204").contains("true");
    }

    @Test
    void rejectsDisallowedUrlsWithoutRequest() {
        for (String url : new String[] {"http://example.com", "https://localhost/secret",
                "https://example.com.evil.test", "https://user@example.com",
                "https://example.com:8443/", "https://127.0.0.1", "not a url"}) {
            assertThat(tool.checkUrlStatus(url).success()).isFalse();
        }
        verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsStatusesAndErrors() throws Exception {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200, 302, 404);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        assertThat(tool.checkUrlStatus("https://EXAMPLE.com/").success()).isTrue();
        assertThat(tool.checkUrlStatus("https://example.com/redirect").success()).isFalse();
        assertThat(tool.checkUrlStatus("https://example.com/missing").status()).isEqualTo(404);

        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("sensitive detail"));
        assertThat(tool.checkUrlStatus("https://example.com/").error()).isEqualTo("Connection failed");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void returnsAfterHeadersAndCancelsBodyWithoutWaitingForEof() throws Exception {
        java.util.concurrent.Flow.Subscription subscription = mock(java.util.concurrent.Flow.Subscription.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpResponse.BodyHandler handler = invocation.getArgument(1);
            var subscriber = handler.apply(mock(HttpResponse.ResponseInfo.class));
            subscriber.onSubscribe(subscription);
            // Never send onNext/onComplete: the remote server leaves its response body open.
            java.io.InputStream body = (java.io.InputStream) subscriber.getBody()
                    .toCompletableFuture().get(1, java.util.concurrent.TimeUnit.SECONDS);
            HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn(body);
            return response;
        });
        assertThat(tool.checkUrlStatus("https://example.com/stream").status()).isEqualTo(200);
        verify(subscription).cancel();
    }
}
