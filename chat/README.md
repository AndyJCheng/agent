# Spring AI Chat Client Demo

This project demonstrates a minimal Spring Boot application that can route chat requests to either OpenAI or Ollama through Spring AI.

## Features

- Supports OpenAI via Spring AI
- Supports Ollama via Spring AI
- Exposes a simple chat HTTP API

## Run

```bash
mvn spring-boot:run
```

## Example requests

```bash
curl http://localhost:8080/api/chat/health

curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Hello","provider":"openai"}'

curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"Hello","provider":"ollama"}'
```

## Configuration

- OpenAI: set `OPENAI_API_KEY`
- Ollama: ensure Ollama is running at `http://localhost:11434` or customize `OLLAMA_BASE_URL`
