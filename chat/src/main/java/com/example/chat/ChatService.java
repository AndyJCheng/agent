package com.example.chat;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ChatService {
    private static final String OLLAMA_SYSTEM_PROMPT = "You can use the checkUrlStatus tool to check the HTTP "
            + "status of an allowed URL when the user asks about its availability or status. "
            + "Only claim a URL was checked if the tool returned a result. Explain the returned status code, "
            + "whether it is a 2xx success, or any error; do not invent a result.";

    private final Optional<OpenAiChatModel> openAiChatModel;
    private final Optional<OllamaChatModel> ollamaChatModel;
    private final ToolCallback[] ollamaTools;
    private final BoundedChatExecutor execution;

    public ChatService(Optional<OpenAiChatModel> openAiChatModel, Optional<OllamaChatModel> ollamaChatModel,
            UrlStatusTool urlStatusTool, BoundedChatExecutor execution) {
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatModel = ollamaChatModel;
        this.ollamaTools = ToolCallbacks.from(urlStatusTool);
        this.execution = execution;
    }

    public String chat(ChatRequest request) {
        String provider = request.providerOrDefault();
        if (!provider.equals("openai") && !provider.equals("ollama")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unsupported provider. Use 'openai' or 'ollama'.");
        }
        ChatResponse response;
        String model = request.model() == null ? "" : request.model().strip();
        if (provider.equals("openai")) {
            OpenAiChatModel client = openAiChatModel.orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "OpenAI is disabled. Enable the openai profile and configure OPENAI_API_KEY."));
            Prompt prompt = model.isEmpty() ? new Prompt(request.message())
                    : new Prompt(request.message(), OpenAiChatOptions.builder().model(model).build());
            try {
                response = execution.call(client, prompt);
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw upstreamFailure(ex);
            }
        } else {
            OllamaChatModel client = ollamaChatModel.orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ollama is disabled."));
            OllamaOptions.Builder options = OllamaOptions.builder().toolCallbacks(ollamaTools);
            if (!model.isEmpty()) {
                options.model(model);
            }
            Prompt prompt = new Prompt(List.of(new SystemMessage(OLLAMA_SYSTEM_PROMPT),
                    new UserMessage(request.message())), options.build());
            try {
                response = execution.call(client, prompt);
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw upstreamFailure(ex);
            }
        }
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider returned no text.");
        }
        return response.getResult().getOutput().getText();
    }

    private ResponseStatusException upstreamFailure(RuntimeException cause) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model provider request failed.", cause);
    }
}
