package com.example.lineage.assistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** Logs the exact user prompt payload only when explicitly enabled for local diagnostics. */
final class AssistantPromptLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("lineage.assistant.prompt");

    private AssistantPromptLogger() {
    }

    static void logIfEnabled(boolean enabled, String provider, String prompt) {
        if (!enabled) {
            return;
        }
        String promptId = UUID.randomUUID().toString();
        LOGGER.info("Outbound RAG prompt begin id={} provider={} characters={} (system instructions excluded)\n{}\n"
                        + "Outbound RAG prompt end id={}",
                promptId, provider, prompt.length(), prompt, promptId);
    }
}
