package com.example.chat;

import java.util.Locale;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank @Size(max = 16000) String message,
        @Size(max = 32) String provider,
        @Size(max = 200) String model) {
    public String providerOrDefault() {
        return provider == null || provider.isBlank()
                ? "ollama" : provider.strip().toLowerCase(Locale.ROOT);
    }
}
