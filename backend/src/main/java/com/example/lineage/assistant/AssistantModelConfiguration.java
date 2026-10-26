package com.example.lineage.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientMode;
import com.github.copilot.rpc.CopilotClientOptions;

@Configuration
public class AssistantModelConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "lineage.assistant", name = "provider", havingValue = "openai")
    @ConditionalOnProperty(prefix = "lineage.assistant", name = "configured", havingValue = "true")
    LineageAssistantModel openAiLineageAssistantModel(ChatClient.Builder chatClientBuilder, Environment environment) {
        requireSetting(environment, "spring.ai.openai.api-key", "OPENAI_API_KEY");
        return new OpenAiLineageAssistantModel(chatClientBuilder, promptLoggingEnabled(environment));
    }

    @Bean
    @ConditionalOnProperty(prefix = "lineage.assistant", name = "provider", havingValue = "gemini")
    @ConditionalOnProperty(prefix = "lineage.assistant", name = "configured", havingValue = "true")
    LineageAssistantModel geminiLineageAssistantModel(Environment environment) {
        String apiKey = requireSetting(environment, "spring.ai.google.genai.api-key", "GEMINI_API_KEY");
        String baseUrl = requireSetting(environment, "spring.ai.google.genai.base-url", "GEMINI_BASE_URL");
        String model = requireSetting(environment, "spring.ai.google.genai.chat.model", "GEMINI_CHAT_MODEL");
        Client client = Client.builder()
                .apiKey(apiKey)
                .httpOptions(HttpOptions.builder().baseUrl(baseUrl).apiVersion("v1beta").build())
                .build();
        GoogleGenAiChatModel chatModel = GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(GoogleGenAiChatOptions.builder().model(model).build())
                .build();
        return new GeminiLineageAssistantModel(ChatClient.builder(chatModel), promptLoggingEnabled(environment));
    }

    @Bean
    @ConditionalOnProperty(prefix = "lineage.assistant", name = "provider", havingValue = "ollama")
    @ConditionalOnProperty(prefix = "lineage.assistant", name = "configured", havingValue = "true")
    LineageAssistantModel ollamaLineageAssistantModel(ChatClient.Builder chatClientBuilder, Environment environment) {
        requireSetting(environment, "spring.ai.ollama.base-url", "OLLAMA_BASE_URL");
        requireSetting(environment, "spring.ai.ollama.chat.model", "OLLAMA_CHAT_MODEL");
        boolean thinkingEnabled = environment.getProperty("lineage.assistant.ollama.think", Boolean.class, false);
        return new OllamaLineageAssistantModel(chatClientBuilder, thinkingEnabled, promptLoggingEnabled(environment));
    }

    @Bean
    @ConditionalOnProperty(prefix = "lineage.assistant", name = "provider", havingValue = "copilot")
    @ConditionalOnProperty(prefix = "lineage.assistant", name = "configured", havingValue = "true")
    LineageAssistantModel copilotLineageAssistantModel(Environment environment) {
        String cliUrl = requireSetting(environment, "lineage.assistant.copilot.cli-url", "COPILOT_CLI_URL");
        String model = requireSetting(environment, "lineage.assistant.copilot.chat.model", "COPILOT_CHAT_MODEL");
        int timeoutSeconds = environment.getProperty("lineage.assistant.copilot.timeout-seconds", Integer.class, 60);
        if (timeoutSeconds < 1 || timeoutSeconds > 600) {
            throw new AssistantModelUnavailableException("COPILOT_TIMEOUT_SECONDS must be between 1 and 600.");
        }
        CopilotClient client = new CopilotClient(new CopilotClientOptions()
                .setMode(CopilotClientMode.EMPTY)
                .setAutoStart(false)
                .setCliUrl(cliUrl));
        return new CopilotLineageAssistantModel(client, model, timeoutSeconds, promptLoggingEnabled(environment));
    }

    private static boolean promptLoggingEnabled(Environment environment) {
        return environment.getProperty("lineage.assistant.log-prompt", Boolean.class, false);
    }

    private static String requireSetting(Environment environment, String property, String environmentVariable) {
        String value = environment.getProperty(property);
        if (!StringUtils.hasText(value)) {
            throw new AssistantModelUnavailableException(
                    "The selected assistant provider is missing required configuration: " + environmentVariable
            );
        }
        return value;
    }

    @Bean
    @ConditionalOnMissingBean(LineageAssistantModel.class)
    LineageAssistantModel unavailableLineageAssistantModel(Environment environment) {
        String provider = environment.getProperty("lineage.assistant.provider", "none");
        String configurationError = environment.getProperty("lineage.assistant.configuration-error");
        String message = StringUtils.hasText(configurationError) ? configurationError : provider.equalsIgnoreCase("none")
                ? "No assistant model is configured. Set LINEAGE_ASSISTANT_PROVIDER to openai, gemini, or ollama."
                : "Assistant model provider '" + provider + "' is not available in this build.";
        return (question, column, evidence) -> {
            throw new AssistantModelUnavailableException(message);
        };
    }
}
