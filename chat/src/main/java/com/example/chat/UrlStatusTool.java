package com.example.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class UrlStatusTool {
    private final HttpClient client;
    private final Set<String> allowedHosts;

    @Autowired
    public UrlStatusTool(@Value("${chat.url-check.allowed-hosts}") String allowedHosts) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build(), allowedHosts);
    }

    // Package-private for tests without network access.
    UrlStatusTool(HttpClient client, String allowedHosts) {
        this.client = client;
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Tool(description = "Check the HTTP status of a URL on a configured trusted HTTPS hostname. "
            + "Only 2xx status codes are successful; redirects are not followed.")
    public UrlStatus checkUrlStatus(@ToolParam(description = "Absolute HTTPS URL to check") String url) {
        if (Thread.currentThread().isInterrupted()) {
            return new UrlStatus(url, null, false, "Request interrupted");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException | NullPointerException ex) {
            return new UrlStatus(url, null, false, "Invalid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443
                || !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            return new UrlStatus(url, null, false, "Only configured HTTPS hosts are allowed");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
            // ofInputStream completes after headers. Closing without reading cancels the body,
            // so a stalled or infinite response body cannot hold the tool open on Java 17.
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                return new UrlStatus(url, status, status >= 200 && status < 300, null);
            }
        } catch (IOException ex) {
            return new UrlStatus(url, null, false, "Connection failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new UrlStatus(url, null, false, "Request interrupted");
        } catch (IllegalArgumentException ex) {
            return new UrlStatus(url, null, false, "Invalid URL");
        }
    }

    public record UrlStatus(String url, Integer status, boolean success, String error) {
    }
}
