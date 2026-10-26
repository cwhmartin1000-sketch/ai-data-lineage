package com.example.lineage.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantProviderEnvironmentPostProcessorTest {

    private final AssistantProviderEnvironmentPostProcessor processor = new AssistantProviderEnvironmentPostProcessor();

    @Test
    void mapsPublicProviderNamesToSpringAiSelectors() {
        assertMapping("none", "none");
        assertMapping("openai", "openai");
        assertMapping("GeMiNi", "none");
        assertMapping("ollama", "ollama");
        assertMapping("copilot", "none");
    }

    @Test
    void rejectsUnknownProviderInsteadOfSilentlyFallingBack() {
        ConfigurableEnvironment environment = environment("other");

        assertThatThrownBy(() -> processor.postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("none, openai, gemini, ollama, copilot");
    }

    @Test
    void missingSelectedCredentialDisablesModelAutoConfigurationAndReportsSettingName() {
        ConfigurableEnvironment environment = environment("gemini");
        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo("none");
        assertThat(environment.getProperty("lineage.assistant.configured", Boolean.class)).isFalse();
        assertThat(environment.getProperty("lineage.assistant.configuration-error"))
                .contains("GEMINI_API_KEY");
    }

    private void assertMapping(String publicProvider, String springAiProvider) {
        ConfigurableEnvironment environment = environment(publicProvider);
        Map<String, Object> configuredProvider = new HashMap<>();
        switch (publicProvider.toLowerCase(java.util.Locale.ROOT)) {
            case "openai" -> configuredProvider.put("spring.ai.openai.api-key", "placeholder");
            case "gemini" -> {
                configuredProvider.put("spring.ai.google.genai.api-key", "placeholder");
                configuredProvider.put("spring.ai.google.genai.base-url", "https://gemini.example.invalid");
                configuredProvider.put("spring.ai.google.genai.chat.model", "test-model");
            }
            case "ollama" -> {
                configuredProvider.put("spring.ai.ollama.base-url", "http://localhost:11434");
                configuredProvider.put("spring.ai.ollama.chat.model", "test-model");
            }
            case "copilot" -> {
                configuredProvider.put("lineage.assistant.copilot.cli-url", "http://localhost:4321");
                configuredProvider.put("lineage.assistant.copilot.chat.model", "test-model");
            }
            default -> { }
        }
        environment.getPropertySources().addFirst(new MapPropertySource("test-provider-settings", configuredProvider));
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("spring.ai.model.chat")).isEqualTo(springAiProvider);
    }

    private ConfigurableEnvironment environment(String provider) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "test-provider", Map.of("LINEAGE_ASSISTANT_PROVIDER", provider)
        ));
        return environment;
    }
}
