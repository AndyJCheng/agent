# URL-checking tool agent

This Spring Boot service uses Spring AI 1.0.0-M6 with OpenAI or Ollama. Its `checkUrlStatus`
Java tool checks HTTP status codes for configured HTTPS hosts; the model chooses
when to call it and summarizes the result. The separate `url_checker.py` script
is **not** used by this service.

Requires Java 17+ and Maven. For the default Ollama provider, start Ollama, then
run from `tool/`:

```bash
ollama pull qwen2.5:3b
mvn spring-boot:run
```

## Run with OpenAI

From `tool/`, set your API key and enable the profile:

```bash
export OPENAI_API_KEY='your-api-key'
export OPENAI_MODEL='gpt-4o-mini'
mvn spring-boot:run -Dspring-boot.run.profiles=openai
```

The `openai` profile selects OpenAI for `/api/agent` and disables Ollama chat;
Ollama does not need to be running. `OPENAI_MODEL` defaults to `gpt-4o-mini`.
Select a model available to your account that supports Chat Completions tool calling.
The request body remains `{"message":"..."}`; the application profile selects the
provider. Starting without the profile selects Ollama and requires no OpenAI key.
Both providers share the URL tool and all execution budgets.

## URL checking

Set `OLLAMA_BASE_URL` and `OLLAMA_MODEL` to override the defaults
(`http://localhost:11434`, `qwen2.5:3b`). Choose a model that supports Ollama
tool calling. Set `URL_CHECK_ALLOWED_HOSTS` to a comma-separated list of trusted
public hostnames (default: `example.com,httpbin.org`). Only exact HTTPS hostnames
on the default port (or port 443) are allowed. Redirects are not followed;
only 2xx responses count as success. Connection timeout is 5 seconds, response-header
request timeout is 10 seconds. The response body is closed immediately without
being downloaded, so streaming responses cannot keep the tool waiting for EOF.
Do not allowlist hosts that resolve to internal services or hosts whose DNS you
do not trust.

```bash
curl http://localhost:8080/api/agent/health
curl -X POST http://localhost:8080/api/agent \
  -H 'Content-Type: application/json' \
  -d '{"message":"Check https://example.com and tell me its status"}'
```

Response: `{"reply":"..."}`. `message` is required, nonblank and at most
16000 characters. Invalid requests return HTTP 400; model failures and exhausted
execution budgets return 502; execution deadline expiry returns 504; capacity
exhaustion returns 503.
The health endpoint checks only application availability, not the selected model service.
Each request is stateless. Tool usage depends on the model; it is not forced.

```bash
mvn test
```

Tests use simulated responses and do not contact model services or public URLs. This demo has no
authentication or rate limiting; keep it private until those are added.

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
