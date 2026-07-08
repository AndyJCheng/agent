package com.example.chat;

import java.util.Optional;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.stereotype.Service;

@Service
public class ChatService {
    private final Optional<OpenAiChatModel> openAiChatModel;
    private final Optional<OllamaChatModel> ollamaChatModel;

    public ChatService(Optional<OpenAiChatModel> openAiChatModel, Optional<OllamaChatModel> ollamaChatModel) {
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatModel = ollamaChatModel;
    }

    public String chat(ChatRequest request) {
        String provider = request.providerOrDefault();
        String message = request.message() == null || request.message().isBlank()
                ? "Hello from Spring AI"
                : request.message();

        return switch (provider) {
            case "openai" -> handleOpenAi(message);
            case "ollama" -> handleOllama(message);
            default -> "Unsupported provider: " + provider + ". Use 'openai' or 'ollama'.";
        };
    }

    private String handleOpenAi(String message) {
        if (openAiChatModel.isEmpty()) {
            return "OpenAI chat model is not configured. Set spring.ai.openai.api-key to enable it.";
        }

        ChatResponse response = openAiChatModel.get().call(new Prompt(new UserMessage(message)));
        return response.getResult().getOutput().getText();
    }

    private String handleOllama(String message) {
        if (ollamaChatModel.isEmpty()) {
            return "Ollama chat model is not configured. Ensure Ollama is running and spring.ai.ollama.base-url is correct.";
        }

        ChatResponse response = ollamaChatModel.get().call(new Prompt(new UserMessage(message)));
        return response.getResult().getOutput().getText();
    }
}
