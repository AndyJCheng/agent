package com.example.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key-not-used-for-network")
@ActiveProfiles("openai")
class OpenAiConfigurationTests {
    @Autowired ApplicationContext context;

    @Test
    void explicitlyEnablesOpenAiAlongsideOllama() {
        assertThat(context.getBeansOfType(OpenAiChatModel.class)).hasSize(1);
        assertThat(context.getBeansOfType(OllamaChatModel.class)).hasSize(1);
    }
}
