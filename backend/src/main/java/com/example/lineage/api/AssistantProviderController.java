package com.example.lineage.api;

import com.example.lineage.assistant.AssistantProviderInfo;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
public class AssistantProviderController {

    private static final Map<String, String> MODEL_PROPERTIES = Map.of(
            "openai", "spring.ai.openai.chat.options.model",
            "gemini", "spring.ai.google.genai.chat.model",
            "ollama", "spring.ai.ollama.chat.model",
            "copilot", "lineage.assistant.copilot.chat.model"
    );

    private final Environment environment;

    public AssistantProviderController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping("/provider")
    public AssistantProviderInfo getProvider() {
        String provider = environment.getProperty("lineage.assistant.provider", "none")
                .trim().toLowerCase(Locale.ROOT);
        String modelProperty = MODEL_PROPERTIES.get(provider);
        String model = modelProperty == null ? null : environment.getProperty(modelProperty);
        boolean ready = environment.getProperty("lineage.assistant.configured", Boolean.class, false);
        return new AssistantProviderInfo(provider, model, ready);
    }
}
