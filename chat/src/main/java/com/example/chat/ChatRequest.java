package com.example.chat;

public record ChatRequest(String message, String provider, String model) {
    public String providerOrDefault() {
        if (provider == null || provider.isBlank()) {
            return "ollama";
        }
        return provider.toLowerCase();
    }
}
