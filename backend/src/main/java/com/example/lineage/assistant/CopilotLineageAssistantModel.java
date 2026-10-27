package com.example.lineage.assistant;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SystemMessageConfig;
import com.example.lineage.callgraph.ProcedureCallEdge;
import com.example.lineage.retrieval.ChunkRetrievalResult;
import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** GitHub Copilot SDK adapter connected to a separately managed local Copilot CLI server. */
public class CopilotLineageAssistantModel implements LineageAssistantModel {

    private final CopilotClient client;
    private final String model;
    private final long timeoutMillis;
    private final boolean promptLoggingEnabled;
    private volatile CompletableFuture<?> startFuture;

    public CopilotLineageAssistantModel(CopilotClient client, String model, int timeoutSeconds) {
        this(client, model, timeoutSeconds, false);
    }

    public CopilotLineageAssistantModel(CopilotClient client, String model, int timeoutSeconds,
                                        boolean promptLoggingEnabled) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        this.client = client;
        this.model = model;
        this.timeoutMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds);
        this.promptLoggingEnabled = promptLoggingEnabled;
    }

    @Override
    public String answer(String question, String column, List<ChunkRetrievalResult> evidence) {
        return answer(question, column, evidence, List.of());
    }

    @Override
    public String answer(String question, String column, List<ChunkRetrievalResult> evidence,
                          List<ProcedureCallEdge> procedureCalls) {
        return complete(AbstractChatClientLineageAssistantModel.SYSTEM_INSTRUCTIONS,
                AbstractChatClientLineageAssistantModel.formatUserPrompt(question, column, evidence, procedureCalls));
    }

    @Override
    public String summarizeFieldAnalysis(String column, String notes) {
        return complete(AbstractChatClientLineageAssistantModel.SUMMARY_INSTRUCTIONS,
                "欄位：" + column + "\n待彙整草稿：\n" + notes);
    }

    private String complete(String systemInstructions, String prompt) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        ensureConnected(deadline);
        String sessionId = "lineage-" + UUID.randomUUID();
        CopilotSession session = null;
        try {
            SessionConfig sessionConfig = new SessionConfig()
                    .setSessionId(sessionId)
                    .setModel(model)
                    .setStreaming(false)
                    .setAvailableTools(List.of())
                    .setTools(List.of())
                    .setMcpServers(Map.of())
                    .setCustomAgents(List.of())
                    .setEnableConfigDiscovery(false)
                    .setEnableSkills(false)
                    .setEnableOnDemandInstructionDiscovery(false)
                    .setSkipCustomInstructions(true)
                    .setOnPermissionRequest((request, invocation) -> CompletableFuture.completedFuture(
                            PermissionRequestResult.reject("Tools are disabled for this assistant.")))
                    .setSystemMessage(new SystemMessageConfig()
                            .setMode(SystemMessageMode.REPLACE)
                            .setContent(systemInstructions));
            session = client.createSession(sessionConfig).get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            AssistantPromptLogger.logIfEnabled(promptLoggingEnabled, "CopilotLineageAssistantModel", prompt);
            long remainingMillis = remainingMillis(deadline);
            AssistantMessageEvent response = session.sendAndWait(
                    new MessageOptions().setPrompt(prompt), remainingMillis)
                    .get(remainingMillis, TimeUnit.MILLISECONDS);
            String content = response == null || response.getData() == null
                    ? null : response.getData().content();
            if (content == null || content.isBlank()) {
                throw new AssistantProviderException(
                        "GitHub Copilot returned an empty answer. Check that the selected model is available to this account.",
                        null);
            }
            return content.trim();
        } catch (TimeoutException exception) {
            throw new AssistantProviderException(
                    "GitHub Copilot did not complete before COPILOT_TIMEOUT_SECONDS elapsed.", exception);
        } catch (AssistantProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AssistantProviderException(
                    "GitHub Copilot could not complete the request. Verify the local CLI server, OAuth login, model access, and organization policy.",
                    exception);
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // Do not log request content or SDK details from session cleanup.
                }
            }
            try {
                client.deleteSession(sessionId).get(Math.min(timeoutMillis, 2_000), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // The response path remains bounded; the unique session ID prevents context reuse.
            }
        }
    }

    private void ensureConnected(long deadline) {
        CompletableFuture<?> current = startFuture;
        if (current == null) {
            synchronized (this) {
                current = startFuture;
                if (current == null) {
                    current = client.start();
                    startFuture = current;
                }
            }
        }
        try {
            current.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new AssistantModelUnavailableException(
                    "GitHub Copilot CLI server did not respond before COPILOT_TIMEOUT_SECONDS elapsed.");
        } catch (Exception exception) {
            throw new AssistantModelUnavailableException(
                    "GitHub Copilot CLI server is unavailable. Start the local headless server and verify `copilot login`.");
        }
    }

    private long remainingMillis(long deadline) throws TimeoutException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining < 1) {
            throw new TimeoutException("Copilot request timeout elapsed.");
        }
        return remaining;
    }

    @PreDestroy
    public void stopClientConnection() {
        CompletableFuture<?> current = startFuture;
        if (current == null || !current.isDone() || current.isCompletedExceptionally()) {
            return;
        }
        try {
            client.stop().get(Math.min(timeoutMillis, 5_000), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // The external CLI process is managed outside this Spring Boot process.
        }
    }
}
