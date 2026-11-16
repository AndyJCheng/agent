package com.example.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class ToolApplicationTests {
	@Autowired AgentService agentService;
	@MockBean OllamaChatModel model;

	@Test
	void contextLoads() {
		when(model.call(any(Prompt.class)))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("ready")))));
		assertThat(agentService.chat("Check https://example.com")).isEqualTo("ready");
		ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
		verify(model).call(captor.capture());
		assertThat(((ToolCallingChatOptions) captor.getValue().getOptions()).getToolCallbacks())
				.extracting(callback -> callback.getName()).contains("checkUrlStatus");
	}

	@Test
	void mapsModelErrorsAndBlankRepliesToBadGateway() {
		when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("upstream detail"));
		assertThatThrownBy(() -> agentService.chat("hello"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						ex -> assertThat(ex.getStatusCode().value()).isEqualTo(502));

		when(model.call(any(Prompt.class)))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(" ")))));
		assertThatThrownBy(() -> agentService.chat("hello"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						ex -> assertThat(ex.getStatusCode().value()).isEqualTo(502));
	}

}
