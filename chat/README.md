# Spring AI Chat Client Demo

A Spring Boot application that routes chat requests to OpenAI or Ollama.
Requires Java 17+ and Maven. Run the following commands from `chat/`.

## Run with Ollama (default)

Start Ollama and download a tool-calling model (`ollama pull qwen2.5:3b`), then:

```bash
mvn spring-boot:run
```

No OpenAI API key is required. Configure Ollama with `OLLAMA_BASE_URL`
(default `http://localhost:11434`) and `OLLAMA_MODEL` (default `qwen2.5:3b`).
Use a model that supports Ollama tool calling if you change this setting.

## Enable OpenAI

Set `OPENAI_API_KEY` in your environment, then:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=openai
```

This enables OpenAI alongside Ollama. The default request provider remains Ollama.
OpenAI embedding, image and audio clients are disabled because this app only uses chat.
Both providers can call the URL tool and share the same execution limits.
Set `OPENAI_MODEL` to select the configured OpenAI model (default `gpt-4o-mini`);
the request's `model` field can override it. Use a model available to your account
that supports Chat Completions tool calling.

## Requests

```bash
curl http://localhost:8080/api/chat/health

curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"检查 https://example.com 是否可用，并解释状态码","provider":"openai"}'

curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"检查 https://example.com 是否可用，并解释状态码","provider":"ollama"}'
```

`message` is required, must not be blank, and is limited to 16000 characters.
`provider` is optional (defaults to `ollama`); surrounding whitespace and case are
ignored. `model` optionally overrides the provider's configured model for this
request; omitted or blank values preserve the configured default.

For either provider, the model decides whether a question needs the `checkUrlStatus` Java
tool and explains its returned status (2xx means success; redirects and other
statuses do not). Tool usage is not forced. To limit outbound requests, the tool
accepts only exact HTTPS hostnames configured via `URL_CHECK_ALLOWED_HOSTS`
(comma-separated, default `example.com,httpbin.org`), on port 443. It does not
follow redirects; connection and response-header timeouts are 5 and 10 seconds.
The response body is closed immediately without being downloaded.
Allowlist only trusted public hosts; do not allowlist internal services or hosts
with untrusted DNS. The Python script under `tool/` is not used by this module.

Successful responses contain `provider` and `reply`. Errors use Problem Detail JSON:

- 400: invalid JSON, invalid fields, or unsupported provider.
- 503: requested provider is disabled or execution capacity is exhausted.
- 502: provider request failed, returned no text, or exhausted execution budgets.
- 504: total execution deadline exceeded.

`/health` reports application availability; it does not probe model services.

## Tests

```bash
mvn test
```

Tests exercise configuration, validation, provider routing, model overrides,
error responses and tool registration without contacting model services or URLs.
Actual tool selection and explanations depend on the selected model.
Test mocks use Mockito's subclass mock maker to avoid requiring JVM agent attachment.

## Execution limits

The application controls the tool loop explicitly; Spring AI internal tool
execution is disabled for these requests. Each request has independent budgets:

| Environment variable | Default | Meaning |
| --- | --- | --- |
| `AGENT_MAX_MODEL_CALLS` | 6 | Maximum model calls, including the final answer |
| `AGENT_MAX_TOOL_CALLS` | 5 | Maximum individual tool calls across all rounds |
| `AGENT_TIMEOUT_MS` | 30000 | Total deadline, including queue wait, models and tools |

An oversized tool batch is rejected before execution. Tools are not executed on
the final permitted model round because no model round remains to summarize them.
Budget exhaustion returns HTTP 502; deadline expiry returns 504. Each application
allows four active executions and sixteen queued requests; excess requests return
503. Expired queued tasks are removed, and running tasks are interrupted. External
model servers may continue computation after client cancellation; no further
model or tool round is started once the application's deadline is reached.

Regression tests use real OpenAI and Ollama model adapters with simulated API responses to
verify tool-result feedback, loop limits and deadline cancellation. The HTTP body
test leaves a response stream unfinished and verifies that the tool cancels it
without waiting for EOF. No external model or website is called by these tests.
