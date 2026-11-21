package com.example.tool;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@WebMvcTest(AgentController.class)
class AgentControllerTests {
    @Autowired MockMvc mvc;
    @MockBean AgentService service;

    @Test
    void returnsReply() throws Exception {
        when(service.chat("Check https://example.com")).thenReturn("Status 200");
        mvc.perform(post("/api/agent").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Check https://example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("Status 200"));
        verify(service).chat("Check https://example.com");
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        for (String body : new String[] {"{}", "{\"message\":\"  \"}",
                "{\"message\":\"" + "x".repeat(16001) + "\"}", "{"}) {
            mvc.perform(post("/api/agent").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        verifyNoInteractions(service);
    }

    @Test
    void handlesModelFailureAndHealth() throws Exception {
        when(service.chat("hello")).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model request failed."));
        mvc.perform(post("/api/agent").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hello\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.status").value(502));
        mvc.perform(get("/api/agent/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
