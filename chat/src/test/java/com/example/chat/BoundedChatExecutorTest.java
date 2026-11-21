package com.example.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.web.server.ResponseStatusException;

class BoundedChatExecutorTest {
    private final OllamaApi api = mock(OllamaApi.class);
    private final UrlStatusTool tool = mock(UrlStatusTool.class);
    private final OllamaChatModel model = OllamaChatModel.builder().ollamaApi(api)
            .defaultOptions(OllamaOptions.builder().model("test-model").build()).build();

    @Test
    void completesRealModelToolRoundTrip() {
        when(tool.checkUrlStatus("https://example.com"))
                .thenReturn(new UrlStatusTool.UrlStatus("https://example.com", 204, true, null));
        when(api.chat(any())).thenReturn(response(true, 1), response(false, 0));
        try (var execution = new BoundedChatExecutor(6, 5, 30000)) {
            assertThat(execution.call(model, prompt()).getResult().getOutput().getText()).isEqualTo("Site is available");
        }
        verify(tool).checkUrlStatus("https://example.com");
        var requests = ArgumentCaptor.forClass(OllamaApi.ChatRequest.class);
        verify(api, times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().get(1).messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo(OllamaApi.Message.Role.TOOL);
            assertThat(message.content()).contains("204").contains("true");
        });
    }

    @Test
    void stopsRepeatedToolCallsAndResetsBudgetForNextRequest() {
        when(tool.checkUrlStatus(any())).thenReturn(new UrlStatusTool.UrlStatus("https://example.com", 200, true, null));
        when(api.chat(any())).thenReturn(response(true, 1));
        try (var execution = new BoundedChatExecutor(6, 2, 30000)) {
            for (int request = 0; request < 2; request++) {
                assertStatus(() -> execution.call(model, prompt()), 502);
            }
        }
        verify(api, times(6)).chat(any());
        verify(tool, times(4)).checkUrlStatus(any());
    }

    @Test
    void enforcesModelRoundLimitBeforeExecutingUnusableToolResult() {
        when(tool.checkUrlStatus(any())).thenReturn(new UrlStatusTool.UrlStatus("https://example.com", 200, true, null));
        when(api.chat(any())).thenReturn(response(true, 1));
        try (var execution = new BoundedChatExecutor(2, 10, 30000)) {
            assertStatus(() -> execution.call(model, prompt()), 502);
        }
        verify(api, times(2)).chat(any());
        verify(tool).checkUrlStatus(any());
    }

    @Test
    void rejectsOversizedBatchBeforeAnyToolsRun() {
        when(api.chat(any())).thenReturn(response(true, 3));
        try (var execution = new BoundedChatExecutor(6, 2, 30000)) {
            assertStatus(() -> execution.call(model, prompt()), 502);
        }
        verifyNoInteractions(tool);
    }

    @Test
    void deadlineCancelsBlockingModelAndDoesNotRunMoreTools() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(api.chat(any())).thenAnswer(invocation -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                // Even a transport returning a tool call after interruption must not execute it.
                Thread.currentThread().interrupt();
            }
            return response(true, 1);
        });
        try (var execution = new BoundedChatExecutor(6, 5, 500)) {
            assertStatus(() -> execution.call(model, prompt()), 504);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
        verify(api).chat(any());
        verifyNoInteractions(tool);
    }

    @Test
    void deadlineAlsoCancelsBlockingToolAndPreventsNextModelRound() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(api.chat(any())).thenReturn(response(true, 1));
        when(tool.checkUrlStatus(any())).thenAnswer(invocation -> {
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return new UrlStatusTool.UrlStatus("https://example.com", null, false, "Interrupted");
        });
        try (var execution = new BoundedChatExecutor(6, 5, 500)) {
            assertStatus(() -> execution.call(model, prompt()), 504);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
        verify(api).chat(any());
        verify(tool).checkUrlStatus(any());
    }

    private Prompt prompt() {
        return new Prompt(List.of(new UserMessage("Check https://example.com")),
                OllamaOptions.builder().toolCallbacks(ToolCallbacks.from(tool)).build());
    }

    private OllamaApi.ChatResponse response(boolean callTool, int count) {
        var calls = java.util.stream.IntStream.range(0, count).mapToObj(index ->
                new OllamaApi.Message.ToolCall(new OllamaApi.Message.ToolCallFunction(
                        "checkUrlStatus", Map.of("url", "https://example.com")))).toList();
        var message = new OllamaApi.Message(OllamaApi.Message.Role.ASSISTANT,
                callTool ? "" : "Site is available", List.of(), calls);
        return new OllamaApi.ChatResponse("test-model", Instant.now(), message, "stop", true,
                0L, 0L, 1, 0L, 1, 0L);
    }

    private void assertStatus(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(status));
    }
}
