package com.example.lineage.assistant;

import org.springframework.ai.chat.client.ChatClient;

/** Gemini Developer API adapter backed by Spring AI Google GenAI ChatModel. */
public class GeminiLineageAssistantModel extends AbstractChatClientLineageAssistantModel {

    public GeminiLineageAssistantModel(ChatClient.Builder chatClientBuilder) {
        super(chatClientBuilder);
    }

    public GeminiLineageAssistantModel(ChatClient.Builder chatClientBuilder, boolean promptLoggingEnabled) {
        super(chatClientBuilder, promptLoggingEnabled);
    }
}
