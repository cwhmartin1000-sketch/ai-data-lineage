package com.example.lineage.assistant;

/** Public, non-secret summary of the Provider selected by backend configuration. */
public record AssistantProviderInfo(String provider, String model, boolean ready) {
}
