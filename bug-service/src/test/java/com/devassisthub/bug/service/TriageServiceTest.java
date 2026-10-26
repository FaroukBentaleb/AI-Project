package com.devassisthub.bug.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.devassisthub.bug.config.TriageProperties;
import com.devassisthub.bug.dto.BugResponse;
import com.devassisthub.bug.dto.TriageRequest;
import com.devassisthub.bug.dto.TriageResponse;
import com.devassisthub.bug.entity.BugCategory;
import com.devassisthub.bug.entity.Severity;
import com.devassisthub.bug.exception.ApiException;
import com.devassisthub.bug.llm.HfChatClient;
import com.devassisthub.bug.llm.HfChatClient.Message;
import com.devassisthub.bug.llm.PiiRedactor;
import com.devassisthub.bug.llm.PromptLibrary;
import com.devassisthub.bug.llm.TriageParser;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class TriageServiceTest {

    private static final String VALID = "{\"severity\":\"CRITICAL\",\"category\":\"SECURITY\","
            + "\"title\":\"Password reset link leaks session token\",\"rationale\":\"Accounts can be taken over.\"}";

    private final HfChatClient client = mock(HfChatClient.class);
    private final BugService bugs = mock(BugService.class);
    private TriageService service;

    @BeforeEach
    void setUp() {
        when(client.model()).thenReturn("org/model");
        service = new TriageService(client, new PromptLibrary(new TriageProperties("v1")), new PiiRedactor(),
                new TriageParser(), bugs);
    }

    private static TriageRequest request(String description) {
        return new TriageRequest("Reset broken", description, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private List<Message> sentMessages(int call) {
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, times(call)).complete(captor.capture());
        return captor.getAllValues().get(call - 1);
    }

    @Test
    void returnsTheValidatedSuggestionWithModelAndPromptVersion() {
        when(client.complete(anyList())).thenReturn(VALID);

        TriageResponse r = service.triage(request("The reset email contains the session token of the user."));

        assertThat(r.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(r.category()).isEqualTo(BugCategory.SECURITY);
        assertThat(r.suggestedTitle()).isEqualTo("Password reset link leaks session token");
        assertThat(r.model()).isEqualTo("org/model");
        assertThat(r.promptVersion()).isEqualTo("v1");
        assertThat(r.redactions()).isZero();
    }

    @Test
    void redactsPersonalDataBeforeItIsSentAndCountsIt() {
        when(client.complete(anyList())).thenReturn(VALID);

        TriageResponse r = service.triage(request("User jane@corp.com got token=abc123xyz in the URL"));

        List<Message> messages = sentMessages(1);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("system");
        String user = messages.get(1).content();
        assertThat(user).contains("[EMAIL]", "token=[SECRET]", "<report>", "(not provided)")
                .doesNotContain("jane@corp.com", "abc123xyz");
        assertThat(r.redactions()).isEqualTo(2);
    }

    @Test
    void anUnusableAnswerGetsOneRepairRoundTrip() {
        when(client.complete(anyList())).thenReturn("It is a critical security bug.", VALID);

        TriageResponse r = service.triage(request("The reset email contains the session token."));

        assertThat(r.severity()).isEqualTo(Severity.CRITICAL);
        List<Message> repair = sentMessages(2);
        assertThat(repair).hasSize(4);
        assertThat(repair.get(2).role()).isEqualTo("assistant");
        assertThat(repair.get(3).content()).contains("the answer contained no JSON object");
    }

    @Test
    void twoUnusableAnswersAreAClearError() {
        when(client.complete(anyList())).thenReturn("nope", "{\"severity\":\"HUGE\"}");

        assertThatThrownBy(() -> service.triage(request("The reset email contains the session token.")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getError()).isEqualTo("LLM_INVALID_OUTPUT");
                });
    }

    @Test
    void providerErrorsPassThroughUnchanged() {
        when(client.complete(anyList())).thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                "LLM_QUOTA_EXCEEDED", "quota"));

        assertThatThrownBy(() -> service.triage(request("The reset email contains the session token.")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getError()).isEqualTo("LLM_QUOTA_EXCEEDED"));
    }

    @Test
    void storedBugTriageReadsTheBugAndRecordsTheSuggestion() {
        when(bugs.triageInput(5L)).thenReturn(new TriageInput("t", "The reset email contains the token.", null, null,
                null, null, null, null));
        when(client.complete(anyList())).thenReturn(VALID);
        BugResponse saved = mock(BugResponse.class);
        when(bugs.recordTriage(eq(5L), any())).thenReturn(saved);

        assertThat(service.triageStored(5L)).isSameAs(saved);
        ArgumentCaptor<TriageResponse> captor = ArgumentCaptor.forClass(TriageResponse.class);
        verify(bugs).recordTriage(eq(5L), captor.capture());
        assertThat(captor.getValue().category()).isEqualTo(BugCategory.SECURITY);
    }

    @Test
    void longStackTracesKeepOnlyTheirHead() {
        String trace = "line\n".repeat(100);

        assertThat(TriageService.stackHead(trace)).contains("(60 more lines)");
        assertThat(TriageService.stackHead(trace).split("\n")).hasSize(TriageService.MAX_STACK_LINES + 1);
    }
}
