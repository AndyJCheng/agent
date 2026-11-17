# agent

Spring AI experiments with Java 17, Spring Boot 3.3.5 and Spring AI 1.0.0-M6.

- [chat](chat/README.md): HTTP chat API with Ollama and opt-in OpenAI support.
- [tool](tool/README.md): OpenAI or Ollama tool-calling agent with a restricted URL status checker.
- `tool/url_checker.py`: independent asynchronous URL checker. Install its
  dependency with `python3 -m pip install -r tool/requirements.txt`, then run
  `python3 tool/url_checker.py`. Only HTTP 2xx responses count as success.

Each Java directory is an independent Maven project. Run tests from the root with:

```bash
mvn -f chat/pom.xml test
mvn -f tool/pom.xml test
```

The two applications use port 8080 by default. To run both, override one port with
`--server.port=8081` in its Spring Boot application arguments.
