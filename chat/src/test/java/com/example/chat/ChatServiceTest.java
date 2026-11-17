package com.example.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.net.http.HttpClient;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.web.server.ResponseStatusException;

class ChatServiceTest {
    private final BoundedChatExecutor execution = new BoundedChatExecutor(6, 5, 30000);

    @org.junit.jupiter.api.AfterEach
    void closeExecution() {
        execution.close();
    }

    @Test
    void missingProviderReturnsServiceUnavailable() {
        ChatService service = service(Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> service.chat(new ChatRequest("Hello", "openai", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));
    }

    @Test
    void unknownProviderReturnsBadRequest() {
        ChatService service = service(Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> service.chat(new ChatRequest("Hello", "unknown", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void routesNormalizedProviderAndPassesOpenAiModel() {
        OpenAiChatModel client = mock(OpenAiChatModel.class);
        when(client.call(any(Prompt.class))).thenReturn(reply());
        ChatService service = service(Optional.of(client), Optional.empty());
        assertThat(service.chat(new ChatRequest("Hello", " OPENAI ", " custom-model "))).isEqualTo("Hi");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(client).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getModel()).isEqualTo("custom-model");
        assertThat(prompt.getValue().getInstructions().get(1).getText()).isEqualTo("Hello");
        assertThat(prompt.getValue().getInstructions()).hasSize(2);
        var options = (org.springframework.ai.openai.OpenAiChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.isInternalToolExecutionEnabled()).isFalse();
    }

    @Test
    void defaultsToOllamaAndPassesModel() {
        OllamaChatModel client = mock(OllamaChatModel.class);
        when(client.call(any(Prompt.class))).thenReturn(reply());
        ChatService service = service(Optional.empty(), Optional.of(client));
        assertThat(service.chat(new ChatRequest("Hello", null, "local-model"))).isEqualTo("Hi");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(client).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getModel()).isEqualTo("local-model");
        assertOllamaToolPrompt(prompt.getValue());
    }

    @Test
    void attachesToolsWithoutModelOverride() {
        OllamaChatModel client = mock(OllamaChatModel.class);
        when(client.call(any(Prompt.class))).thenReturn(reply());
        ChatService service = service(Optional.empty(), Optional.of(client));
        assertThat(service.chat(new ChatRequest("Check https://example.com", "ollama", null))).isEqualTo("Hi");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(client).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getModel()).isNull();
        assertOllamaToolPrompt(prompt.getValue());
        assertThat(prompt.getValue().getInstructions().get(1)).isInstanceOf(UserMessage.class);
        assertThat(prompt.getValue().getInstructions().get(1).getText()).isEqualTo("Check https://example.com");
    }

    @Test
    void mapsUpstreamFailuresAndEmptyResponsesToBadGateway() {
        OllamaChatModel client = mock(OllamaChatModel.class);
        ChatService service = service(Optional.empty(), Optional.of(client));
        when(client.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret upstream detail"));
        assertBadGateway(service);
        when(client.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        assertBadGateway(service);
    }

    private void assertBadGateway(ChatService service) {
        assertThatThrownBy(() -> service.chat(new ChatRequest("Hello", null, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(502));
    }

    private ChatResponse reply() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("Hi"))));
    }

    private ChatService service(Optional<OpenAiChatModel> openai, Optional<OllamaChatModel> ollama) {
        return new ChatService(openai, ollama, new UrlStatusTool(mock(HttpClient.class), "example.com"), execution);
    }

    private void assertOllamaToolPrompt(Prompt prompt) {
        assertThat(prompt.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getInstructions().get(0).getText()).contains("Explain the returned status code");
        OllamaOptions options = (OllamaOptions) prompt.getOptions();
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolCallbacks().get(0).getName()).isEqualTo("checkUrlStatus");
    }
}
