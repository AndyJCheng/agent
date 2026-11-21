package com.example.tool;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentService {
    private final Optional<OpenAiChatModel> openAiModel;
    private final Optional<OllamaChatModel> ollamaModel;
    private final String provider;
    private final UrlStatusTool urlStatusTool;
    private final BoundedChatExecutor execution;

    public AgentService(Optional<OpenAiChatModel> openAiModel, Optional<OllamaChatModel> ollamaModel,
            @Value("${agent.provider:ollama}") String provider,
            UrlStatusTool urlStatusTool, BoundedChatExecutor execution) {
        this.openAiModel = openAiModel;
        this.ollamaModel = ollamaModel;
        this.provider = provider.strip().toLowerCase(Locale.ROOT);
        if (!this.provider.equals("openai") && !this.provider.equals("ollama")) {
            throw new IllegalArgumentException("agent.provider must be openai or ollama");
        }
        this.urlStatusTool = urlStatusTool;
        this.execution = execution;
    }

    public String chat(String message) {
        ChatModel model = provider.equals("openai")
                ? openAiModel.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "OpenAI is disabled. Enable the openai profile and configure OPENAI_API_KEY."))
                : ollamaModel.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Ollama is disabled."));
        var options = ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(urlStatusTool)).build();
        var prompt = new Prompt(List.of(new SystemMessage(
                "You can check the HTTP status of allowed URLs using the checkUrlStatus tool. "
                + "Use it when asked to verify a URL. Never claim you checked a URL unless the tool returned a result."),
                new UserMessage(message)), options);
        var response = execution.call(model, prompt);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model returned no text.");
        }
        return response.getResult().getOutput().getText();
    }
}
