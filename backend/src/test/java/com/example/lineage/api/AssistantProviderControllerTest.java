package com.example.lineage.api;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantProviderControllerTest {

    @Test
    void returnsSelectedProviderAndModelWithoutExposingSecrets() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-provider", Map.of(
                "lineage.assistant.provider", "gemini",
                "lineage.assistant.configured", true,
                "spring.ai.google.genai.chat.model", "test-model",
                "spring.ai.google.genai.api-key", "must-not-be-returned"
        )));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AssistantProviderController(environment)).build();

        mockMvc.perform(get("/api/assistant/provider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("gemini"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("must-not-be-returned"))));
    }

    @Test
    void reportsCopilotModelAndDoesNotExposeCredentialLikeSettings() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-provider", Map.of(
                "lineage.assistant.provider", "copilot",
                "lineage.assistant.configured", true,
                "lineage.assistant.copilot.chat.model", "test-copilot-model",
                "lineage.assistant.copilot.cli-url", "http://localhost:4321",
                "lineage.assistant.copilot.token", "must-not-be-returned"
        )));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AssistantProviderController(environment)).build();

        mockMvc.perform(get("/api/assistant/provider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("copilot"))
                .andExpect(jsonPath("$.model").value("test-copilot-model"))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("must-not-be-returned"))));
    }
}
