package com.example.tool;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentRequest(@NotBlank @Size(max = 16000) String message) {
}
