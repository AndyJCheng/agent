package com.example.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.ai.openai.OpenAiChatModel;

@SpringBootTest(properties = "spring.ai.openai.api-key=")
@AutoConfigureMockMvc
class ChatApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void startsWithoutOpenAiKey() throws Exception {
        assertThat(context.getBeansOfType(OpenAiChatModel.class)).isEmpty();
        mvc.perform(get("/api/chat/health")).andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidMessagesAndMalformedJson() throws Exception {
        for (String body : new String[] {"{}", "{\"message\":\" \"}",
                "{\"message\":\"" + "x".repeat(16001) + "\"}", "{"}) {
            mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));
        }
    }

    @Test
    void reportsUnknownAndDisabledProviders() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hi\",\"provider\":\"unknown\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Hi\",\"provider\":\"openai\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }
}
