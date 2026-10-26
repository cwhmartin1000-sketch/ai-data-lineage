package com.example.lineage.assistant;

import org.springframework.ai.chat.client.ChatClient;

/** OpenAI adapter; assistant business logic depends only on LineageAssistantModel. */
public class OpenAiLineageAssistantModel extends AbstractChatClientLineageAssistantModel {

    public OpenAiLineageAssistantModel(ChatClient.Builder chatClientBuilder) {
        super(chatClientBuilder);
    }

    public OpenAiLineageAssistantModel(ChatClient.Builder chatClientBuilder, boolean promptLoggingEnabled) {
        super(chatClientBuilder, promptLoggingEnabled);
    }
}
