package com.example.lineage.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OllamaLineageAssistantModelTest {

    @Test
    void disablesQwenThinkingInChatClientApiOptionsByDefault() {
        ChatClient.Builder builder = mockBuilder();

        new OllamaLineageAssistantModel(builder);

        assertThinkOption(builder, ThinkOption.ThinkBoolean.DISABLED);
    }

    @Test
    void enablesThinkingOnlyWhenExplicitlyConfigured() {
        ChatClient.Builder builder = mockBuilder();

        new OllamaLineageAssistantModel(builder, true);

        assertThinkOption(builder, ThinkOption.ThinkBoolean.ENABLED);
    }

    private ChatClient.Builder mockBuilder() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.defaultOptions(any(ChatOptions.class))).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return builder;
    }

    private void assertThinkOption(ChatClient.Builder builder, ThinkOption expected) {
        var options = org.mockito.ArgumentCaptor.forClass(ChatOptions.class);
        verify(builder).defaultOptions(options.capture());
        assertThat(options.getValue()).isInstanceOf(OllamaChatOptions.class);
        assertThat(((OllamaChatOptions) options.getValue()).getThinkOption()).isEqualTo(expected);
    }
}
