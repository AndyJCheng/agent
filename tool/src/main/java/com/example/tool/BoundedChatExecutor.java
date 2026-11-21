package com.example.tool;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Owns the tool loop so execution budgets are enforced outside the model. */
@Component
public class BoundedChatExecutor implements AutoCloseable {
    private final int maxModelCalls;
    private final int maxToolCalls;
    private final long timeoutMillis;
    private final ThreadPoolExecutor workers;
    private final ToolCallingManager tools = ToolCallingManager.builder().build();

    public BoundedChatExecutor(
            @Value("${agent.execution.max-model-calls:6}") int maxModelCalls,
            @Value("${agent.execution.max-tool-calls:5}") int maxToolCalls,
            @Value("${agent.execution.timeout-ms:30000}") long timeoutMillis) {
        if (maxModelCalls < 1 || maxToolCalls < 0 || timeoutMillis < 1) {
            throw new IllegalArgumentException("Invalid agent execution limits");
        }
        this.maxModelCalls = maxModelCalls;
        this.maxToolCalls = maxToolCalls;
        this.timeoutMillis = timeoutMillis;
        AtomicInteger sequence = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, "agent-execution-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public ChatResponse call(ChatModel model, Prompt prompt) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Future<ChatResponse> task;
        try {
            task = workers.submit(() -> execute(model, prompt, deadline));
        } catch (RejectedExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent capacity exceeded.");
        }
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return task.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            cancel(task);
            throw deadlineExceeded();
        } catch (InterruptedException ex) {
            cancel(task);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent request interrupted.");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof ResponseStatusException status) throw status;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Model request failed.", ex.getCause());
        }
    }

    private ChatResponse execute(ChatModel model, Prompt initial, long deadline) {
        var options = initial.getOptions() == null ? null : initial.getOptions().copy();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            // Disable Spring AI's recursive loop; budgets apply to every model/tool round below.
            toolOptions.setInternalToolExecutionEnabled(false);
        }
        Prompt prompt = new Prompt(initial.getInstructions(), options);
        int usedTools = 0;
        for (int round = 0; round < maxModelCalls; round++) {
            checkDeadline(deadline);
            ChatResponse response = model.call(prompt);
            checkDeadline(deadline);
            if (response == null || !response.hasToolCalls()) return response;
            int requestedTools = response.getResults().stream()
                    .mapToInt(result -> result.getOutput().getToolCalls().size()).sum();
            if (round + 1 >= maxModelCalls || requestedTools > maxToolCalls - usedTools) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent execution budget exceeded.");
            }
            usedTools += requestedTools;
            var result = tools.executeToolCalls(prompt, response);
            checkDeadline(deadline);
            if (result.returnDirect()) {
                return ChatResponse.builder().from(response)
                        .generations(org.springframework.ai.model.tool.ToolExecutionResult.buildGenerations(result)).build();
            }
            prompt = new Prompt(result.conversationHistory(), options);
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Agent execution budget exceeded.");
    }

    private void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
            throw deadlineExceeded();
        }
    }

    private ResponseStatusException deadlineExceeded() {
        return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Agent execution deadline exceeded.");
    }

    private void cancel(Future<?> task) {
        task.cancel(true);
        if (task instanceof Runnable runnable) workers.remove(runnable);
    }

    @Override
    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
