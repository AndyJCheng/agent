package com.example.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class ChatServiceTest {
    @Test
    void returnsHelpfulMessageWhenNoProviderConfigured() {
        ChatService service = new ChatService(Optional.empty(), Optional.empty());

        String reply = service.chat(new ChatRequest("Hello", "openai", null));

        assertThat(reply).contains("OpenAI chat model is not configured");
    }
}
