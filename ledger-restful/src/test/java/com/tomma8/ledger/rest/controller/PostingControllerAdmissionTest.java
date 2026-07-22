package com.tomma8.ledger.rest.controller;

import com.tomma8.ledger.domain.command.CommandResult;
import com.tomma8.ledger.domain.command.PostingCommand;
import com.tomma8.ledger.domain.model.LedgerErrorCode;
import com.tomma8.ledger.raft.NodeRole;
import com.tomma8.ledger.rest.config.properties.LedgerProperties;
import com.tomma8.ledger.service.PostingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PostingController — admission control / backpressure")
class PostingControllerAdmissionTest {

    private static LedgerProperties postingProps(int maxInflight, int acquireMs) {
        LedgerProperties props = new LedgerProperties();
        props.getPosting().setMaxInflight(maxInflight);
        props.getPosting().setInflightAcquireMs(acquireMs);
        return props;
    }

    private static NodeRole leader() {
        NodeRole nr = new NodeRole();
        nr.setLeader("test-node", 1);
        return nr;
    }

    private static PostingService completingService() {
        return new PostingService(null) {
            @Override public CommandResult post(PostingCommand command) {
                return CommandResult.completed("JNL-TEST");
            }
        };
    }

    private static Map<String, Object> validBody() {
        return Map.of(
                "requestId", "req-1",
                "businessEventType", "TRANSFER",
                "businessEventRef", "ref-1",
                "valueDate", "2026-06-16",
                "legs", List.of(Map.of(
                        "legId", "leg-1", "postingType", "TRANSFER", "amount", "1", "currency", "USD",
                        "lines", List.of(
                                Map.of("accountId", "A", "balanceType", "AVAILABLE_BALANCE", "position", "CURRENT", "entryType", "DEBIT", "description", "d"),
                                Map.of("accountId", "B", "balanceType", "AVAILABLE_BALANCE", "position", "CURRENT", "entryType", "CREDIT", "description", "c")))));
    }

    @Test
    @DisplayName("TC-NFR-ADM-01 saturated (0 permits) → 503 QUEUE_FULL, load shed")
    void saturated_shedsWith503() {
        PostingController c = new PostingController(
                completingService(), null, null, null, leader(), new SimpleMeterRegistry(), postingProps(0, 10));

        ResponseEntity<?> resp = c.post(validBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("errorCodes").toString()).contains(LedgerErrorCode.QUEUE_FULL.name());
        assertThat(resp.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    @DisplayName("TC-NFR-ADM-02 permits available → posting passes (200 COMPLETED) + permit released")
    void available_passesAndReleases() {
        PostingController c = new PostingController(
                completingService(), null, null, null, leader(), new SimpleMeterRegistry(), postingProps(2, 50));

        for (int i = 0; i < 2; i++) {
            ResponseEntity<?> resp = c.post(validBody());
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) resp.getBody();
            assertThat(body.get("status")).isEqualTo(CommandResult.COMPLETED);
        }
    }
}
