package com.example.lineage.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/** Local Ollama adapter backed by Spring AI OllamaChatModel. */
public class OllamaLineageAssistantModel extends AbstractChatClientLineageAssistantModel {

    public OllamaLineageAssistantModel(ChatClient.Builder chatClientBuilder) {
        this(chatClientBuilder, false);
    }

    public OllamaLineageAssistantModel(ChatClient.Builder chatClientBuilder, boolean thinkingEnabled) {
        this(chatClientBuilder, thinkingEnabled, false);
    }

    public OllamaLineageAssistantModel(ChatClient.Builder chatClientBuilder, boolean thinkingEnabled,
                                       boolean promptLoggingEnabled) {
        super(chatClientBuilder.defaultOptions(thinkingEnabled
                ? OllamaChatOptions.builder().enableThinking().build()
                : OllamaChatOptions.builder().disableThinking().build()), promptLoggingEnabled);
    }
}
