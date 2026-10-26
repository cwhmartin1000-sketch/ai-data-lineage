package com.example.lineage.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Maps the public provider name to Spring AI's provider selector before auto-configuration runs. */
public class AssistantProviderEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Map<String, String> SPRING_AI_PROVIDERS = Map.of(
            "none", "none",
            "openai", "openai",
            // Gemini is created from Spring AI's GoogleGenAiChatModel so its SDK endpoint is configurable.
            "gemini", "none",
            "ollama", "ollama",
            // Copilot uses the official SDK connected to a separately managed local CLI server.
            "copilot", "none"
    );
    private static final Set<String> SUPPORTED_PROVIDERS = SPRING_AI_PROVIDERS.keySet();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String provider = environment.getProperty("LINEAGE_ASSISTANT_PROVIDER", "none")
                .trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException(
                    "Unsupported LINEAGE_ASSISTANT_PROVIDER. Supported values are: none, openai, gemini, ollama, copilot."
            );
        }
        String missingSetting = missingRequiredSetting(provider, environment);
        String springAiProvider = missingSetting == null ? SPRING_AI_PROVIDERS.get(provider) : "none";
        Map<String, Object> properties = new java.util.HashMap<>();
        properties.put("spring.ai.model.chat", springAiProvider);
        properties.put("lineage.assistant.configured", missingSetting == null && !provider.equals("none"));
        if (missingSetting != null) {
            properties.put("lineage.assistant.configuration-error",
                    "Assistant provider '" + provider + "' is selected but required setting " + missingSetting + " is missing.");
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                "lineageAssistantProviderMapping",
                properties
        ));
    }

    private String missingRequiredSetting(String provider, ConfigurableEnvironment environment) {
        return switch (provider) {
            case "openai" -> hasText(environment.getProperty("spring.ai.openai.api-key"))
                    ? null : "OPENAI_API_KEY";
            case "gemini" -> firstMissing(environment,
                    "spring.ai.google.genai.api-key", "GEMINI_API_KEY",
                    "spring.ai.google.genai.base-url", "GEMINI_BASE_URL",
                    "spring.ai.google.genai.chat.model", "GEMINI_CHAT_MODEL");
            case "ollama" -> firstMissing(environment,
                    "spring.ai.ollama.base-url", "OLLAMA_BASE_URL",
                    "spring.ai.ollama.chat.model", "OLLAMA_CHAT_MODEL");
            case "copilot" -> firstMissing(environment,
                    "lineage.assistant.copilot.cli-url", "COPILOT_CLI_URL",
                    "lineage.assistant.copilot.chat.model", "COPILOT_CHAT_MODEL");
            default -> null;
        };
    }

    private String firstMissing(ConfigurableEnvironment environment, String... propertyAndEnvironmentNames) {
        for (int index = 0; index < propertyAndEnvironmentNames.length; index += 2) {
            if (!hasText(environment.getProperty(propertyAndEnvironmentNames[index]))) {
                return propertyAndEnvironmentNames[index + 1];
            }
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
