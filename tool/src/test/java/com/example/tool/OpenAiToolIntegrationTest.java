package com.example.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class OpenAiToolIntegrationTest {
    private final OpenAiApi api = mock(OpenAiApi.class);
    private final UrlStatusTool tool = mock(UrlStatusTool.class);
    private final OllamaChatModel ollama = mock(OllamaChatModel.class);
    private final OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model("test-model").build()).build();
    private final BoundedChatExecutor execution = new BoundedChatExecutor(6, 2, 30000);

    @AfterEach
    void close() {
        execution.close();
    }

    @Test
    void openAiExecutesToolAndReceivesMatchingResult() {
        when(tool.checkUrlStatus("https://example.com"))
                .thenReturn(new UrlStatusTool.UrlStatus("https://example.com", 204, true, null));
        when(api.chatCompletionEntity(any(), any())).thenReturn(response(true), response(false));
        assertThat(chat()).isEqualTo("Site is available");
        verify(tool).checkUrlStatus("https://example.com");
        verifyNoInteractions(ollama);
        var requests = ArgumentCaptor.forClass(OpenAiApi.ChatCompletionRequest.class);
        verify(api, times(2)).chatCompletionEntity(requests.capture(), any());
        assertThat(requests.getAllValues().get(0).tools()).hasSize(1);
        assertThat(requests.getAllValues().get(0).model()).isEqualTo("test-model");
        assertThat(requests.getAllValues().get(1).messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo(OpenAiApi.ChatCompletionMessage.Role.TOOL);
            assertThat(message.toolCallId()).isEqualTo("call-1");
            assertThat(message.content()).contains("204").contains("true");
        });
    }

    @Test
    void repeatedOpenAiToolsAreBoundedByTheSameBudget() {
        when(tool.checkUrlStatus(any())).thenReturn(new UrlStatusTool.UrlStatus("https://example.com", 200, true, null));
        when(api.chatCompletionEntity(any(), any())).thenReturn(response(true));
        assertThatThrownBy(this::chat).isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(502));
        verify(api, times(3)).chatCompletionEntity(any(), any());
        verify(tool, times(2)).checkUrlStatus(any());
        verifyNoInteractions(ollama);
    }

    private String chat() {
        return new AgentService(Optional.of(model), Optional.of(ollama), "openai", tool, execution)
                .chat("Check https://example.com");
    }

    private ResponseEntity<OpenAiApi.ChatCompletion> response(boolean callTool) {
        var calls = callTool ? List.of(new OpenAiApi.ChatCompletionMessage.ToolCall("call-1", "function",
                new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction("checkUrlStatus",
                        "{\"url\":\"https://example.com\"}"))) : List.<OpenAiApi.ChatCompletionMessage.ToolCall>of();
        var message = new OpenAiApi.ChatCompletionMessage(callTool ? "" : "Site is available",
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null, calls, null, null);
        var choice = new OpenAiApi.ChatCompletion.Choice(callTool ? OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS
                : OpenAiApi.ChatCompletionFinishReason.STOP, 0, message, null);
        return ResponseEntity.ok(new OpenAiApi.ChatCompletion("completion-1", List.of(choice), 0L,
                "test-model", null, null, "chat.completion", null));
    }
}
