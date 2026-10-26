package com.example.lineage.assistant;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.SessionConfig;
import com.example.lineage.callgraph.ProcedureCallEdge;
import com.example.lineage.retrieval.ChunkRetrievalResult;
import com.example.lineage.retrieval.MatchedLine;
import com.example.lineage.retrieval.SqlColumnDependencyAnalyzer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CopilotLineageAssistantModelTest {

    @Test
    void sendsGroundedEvidenceInAnIsolatedNoToolsSessionAndReturnsText() throws Exception {
        CopilotClient client = mock(CopilotClient.class);
        CopilotSession session = mock(CopilotSession.class);
        AssistantMessageEvent event = mock(AssistantMessageEvent.class);
        AssistantMessageEvent.AssistantMessageEventData data = mock(AssistantMessageEvent.AssistantMessageEventData.class);
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any(SessionConfig.class))).thenReturn(CompletableFuture.completedFuture(session));
        when(session.sendAndWait(any(MessageOptions.class), anyLong())).thenReturn(CompletableFuture.completedFuture(event));
        when(event.getData()).thenReturn(data);
        when(data.content()).thenReturn("有證據的回答");
        when(client.deleteSession(anyString())).thenReturn(CompletableFuture.completedFuture(null));

        SqlColumnDependencyAnalyzer.ColumnDependency dependency = new SqlColumnDependencyAnalyzer.ColumnDependency(
                "TGT_POLICY", "EFFECTIVE_DATE", "STG_POLICY_TRANSFORMED", "CALCULATED_EFFECTIVE_DATE",
                "load-chunk", "PKG_POLICY_LOAD", "LOAD_TARGET", "PKG_POLICY_LOAD.pkb", 20, 31, 27);
        ChunkRetrievalResult chunk = new ChunkRetrievalResult("load-chunk", "PKG_POLICY_LOAD", "LOAD_TARGET",
                "PKG_POLICY_LOAD.pkb", 20, 31, 10,
                List.of(new MatchedLine(27, "t.EFFECTIVE_DATE = s.CALCULATED_EFFECTIVE_DATE")),
                "t.EFFECTIVE_DATE = s.CALCULATED_EFFECTIVE_DATE", List.of(dependency));
        ProcedureCallEdge call = new ProcedureCallEdge("PKG_POLICY_LOAD", "RUN_FULL_LOAD",
                "PKG_POLICY_TRANSFORM", "RUN_TRANSFORM", "PKG_POLICY_LOAD.pkb", 6,
                "PKG_POLICY_TRANSFORM.RUN_TRANSFORM;", true);

        CopilotLineageAssistantModel model = new CopilotLineageAssistantModel(client, "account-model", 10);
        assertThat(model.answer("欄位從哪裡來？", "TGT_POLICY.EFFECTIVE_DATE", List.of(chunk), List.of(call)))
                .isEqualTo("有證據的回答");
        assertThat(model.answer("再問一次", "TGT_POLICY.EFFECTIVE_DATE", List.of(chunk), List.of(call)))
                .isEqualTo("有證據的回答");

        ArgumentCaptor<SessionConfig> configCaptor = ArgumentCaptor.forClass(SessionConfig.class);
        verify(client, org.mockito.Mockito.times(2)).createSession(configCaptor.capture());
        List<SessionConfig> configs = configCaptor.getAllValues();
        SessionConfig config = configs.get(0);
        assertThat(configs).extracting(SessionConfig::getSessionId).doesNotHaveDuplicates();
        assertThat(config.getModel()).isEqualTo("account-model");
        assertThat(config.getAvailableTools()).isEmpty();
        assertThat(config.getTools()).isEmpty();
        assertThat(config.getMcpServers()).isEmpty();
        assertThat(config.getCustomAgents()).isEmpty();
        assertThat(config.getEnableConfigDiscovery()).contains(false);
        assertThat(config.getEnableSkills()).contains(false);
        assertThat(config.getSkipCustomInstructions()).contains(true);
        assertThat(config.getOnPermissionRequest().handle(new PermissionRequest(), new PermissionInvocation())
                .join().getKind()).isEqualTo("reject");

        ArgumentCaptor<MessageOptions> promptCaptor = ArgumentCaptor.forClass(MessageOptions.class);
        verify(session, org.mockito.Mockito.times(2)).sendAndWait(promptCaptor.capture(), anyLong());
        assertThat(promptCaptor.getAllValues().get(0).getPrompt())
                .contains("Verified SQL Dependencies")
                .contains("STG_POLICY_TRANSFORMED.CALCULATED_EFFECTIVE_DATE -> TGT_POLICY.EFFECTIVE_DATE")
                .contains("Verified Procedure Calls")
                .contains("PKG_POLICY_LOAD.RUN_FULL_LOAD -> PKG_POLICY_TRANSFORM.RUN_TRANSFORM")
                .contains("line=27")
                .contains("[S1] file=PKG_POLICY_LOAD.pkb");
        configs.forEach(sessionConfig -> verify(client).deleteSession(sessionConfig.getSessionId()));
    }

    @Test
    void unavailableCliHasActionableErrorWithoutEchoingSensitiveDetails() {
        CopilotClient client = mock(CopilotClient.class);
        when(client.start()).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("secret-token-value")));
        CopilotLineageAssistantModel model = new CopilotLineageAssistantModel(client, "account-model", 1);

        assertThatThrownBy(() -> model.answer("question", "COLUMN", List.of()))
                .isInstanceOf(AssistantModelUnavailableException.class)
                .hasMessageContaining("local headless server")
                .hasMessageNotContaining("secret-token-value");
    }

    @Test
    void requestTimeoutIsReportedAndSessionIsStillDeleted() {
        CopilotClient client = mock(CopilotClient.class);
        CopilotSession session = mock(CopilotSession.class);
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any(SessionConfig.class))).thenReturn(CompletableFuture.completedFuture(session));
        when(session.sendAndWait(any(MessageOptions.class), anyLong())).thenReturn(new CompletableFuture<>());
        when(client.deleteSession(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        CopilotLineageAssistantModel model = new CopilotLineageAssistantModel(client, "account-model", 1);

        assertThatThrownBy(() -> model.answer("question", "COLUMN", List.of()))
                .isInstanceOf(AssistantProviderException.class)
                .hasMessageContaining("COPILOT_TIMEOUT_SECONDS");
        verify(client).deleteSession(anyString());
    }

    @Test
    void doesNotLogPromptSourceOrSdkFailureDetails() {
        CopilotClient client = mock(CopilotClient.class);
        when(client.start()).thenReturn(CompletableFuture.failedFuture(
                new IllegalStateException("TOKEN-SECRET-DETAIL")));
        CopilotLineageAssistantModel model = new CopilotLineageAssistantModel(client, "account-model", 1);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
        appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        appender.start();
        root.addAppender(appender);
        try {
            assertThatThrownBy(() -> model.answer("QUESTION-SECRET", "COLUMN", List.of(
                    new ChunkRetrievalResult("id", "PKG", "PROC", "secret.pkb", 1, 1, 1,
                            List.of(new MatchedLine(1, "SOURCE-CODE-SECRET")), "SOURCE-CODE-SECRET"))))
                    .isInstanceOf(AssistantModelUnavailableException.class);

            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(
                        "TOKEN-SECRET-DETAIL", "QUESTION-SECRET", "SOURCE-CODE-SECRET");
            });
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
