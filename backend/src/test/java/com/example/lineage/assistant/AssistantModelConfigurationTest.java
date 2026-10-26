package com.example.lineage.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.github.copilot.rpc.CopilotClientOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantModelConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AssistantModelConfiguration.class)
            .withBean(ChatClient.Builder.class, () -> {
                ChatClient.Builder builder = mock(ChatClient.Builder.class);
                when(builder.defaultOptions(any(ChatOptions.class))).thenReturn(builder);
                return builder;
            });

    @Test
    void selectsOpenAiAdapterWhenConfigured() {
        assertSelected("openai", "lineage.assistant.configured=true", "spring.ai.openai.api-key=test-key", OpenAiLineageAssistantModel.class);
    }

    @Test
    void selectsGeminiWithoutRequiringOpenAiCredentials() {
        assertSelected("gemini", new String[]{
                "spring.ai.google.genai.api-key=test-key",
                "lineage.assistant.configured=true",
                "spring.ai.google.genai.base-url=https://gemini.example.invalid",
                "spring.ai.google.genai.chat.model=test-model"
        }, GeminiLineageAssistantModel.class);
    }

    @Test
    void selectsOllamaUsingConfiguredEndpointAndModelWithoutCloudCredentials() {
        assertSelected("ollama", new String[]{
                "spring.ai.ollama.base-url=http://localhost:11434",
                "lineage.assistant.configured=true",
                "spring.ai.ollama.chat.model=test-model"
        }, OllamaLineageAssistantModel.class);
    }

    @Test
    void selectsCopilotWithoutRequiringCloudProviderCredentials() {
        contextRunner.withPropertyValues(
                        "lineage.assistant.provider=copilot",
                        "lineage.assistant.configured=true",
                        "lineage.assistant.copilot.cli-url=http://localhost:4321",
                        "lineage.assistant.copilot.chat.model=test-model")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(LineageAssistantModel.class);
                    assertThat(context.getBean(LineageAssistantModel.class))
                            .isInstanceOf(CopilotLineageAssistantModel.class);
                });
    }

    @Test
    void copilotTimeoutConfigurationIsBounded() {
        contextRunner.withPropertyValues(
                        "lineage.assistant.provider=copilot",
                        "lineage.assistant.configured=true",
                        "lineage.assistant.copilot.cli-url=http://localhost:4321",
                        "lineage.assistant.copilot.chat.model=test-model",
                        "lineage.assistant.copilot.timeout-seconds=601")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("COPILOT_TIMEOUT_SECONDS");
                });
    }

    @Test
    void missingSelectedProviderConfigurationFailsWithActionableMessage() {
        contextRunner.withPropertyValues("lineage.assistant.provider=gemini", "lineage.assistant.configured=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("GEMINI_API_KEY");
                });
    }

    @Test
    void noneProviderKeepsApplicationAvailableAndReportsNoModel() {
        contextRunner.withPropertyValues("lineage.assistant.provider=none", "lineage.assistant.configured=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LineageAssistantModel.class);
                    assertThat(context.getBean(LineageAssistantModel.class))
                            .isInstanceOfSatisfying(LineageAssistantModel.class, model ->
                                    org.assertj.core.api.Assertions.assertThatThrownBy(
                                                    () -> model.answer("question", null, java.util.List.of()))
                                            .isInstanceOf(AssistantModelUnavailableException.class)
                                            .hasMessageContaining("LINEAGE_ASSISTANT_PROVIDER"));
                });
    }

    private void assertSelected(String provider, String credentialOrEndpoint, Class<?> expectedType) {
        assertSelected(provider, new String[]{credentialOrEndpoint}, expectedType);
    }

    private void assertSelected(String provider, String setting1, String setting2, Class<?> expectedType) {
        assertSelected(provider, new String[]{setting1, setting2}, expectedType);
    }

    private void assertSelected(String provider, String[] settings, Class<?> expectedType) {
        String[] properties = new String[settings.length + 1];
        properties[0] = "lineage.assistant.provider=" + provider;
        for (int index = 0; index < settings.length; index++) {
            properties[index + 1] = settings[index];
        }
        contextRunner.withPropertyValues(properties).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LineageAssistantModel.class);
            assertThat(context.getBean(LineageAssistantModel.class)).isInstanceOf(expectedType);
        });
    }
}
