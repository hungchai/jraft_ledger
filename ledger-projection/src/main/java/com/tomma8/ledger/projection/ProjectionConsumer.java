package com.tomma8.ledger.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin Kafka adapter: parses projection events and delegates all persistence
 * to {@link ProjectionWriter}. No direct DB access here.
 */
@Service
public class ProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProjectionConsumer.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final ProjectionWriter writer;

    public ProjectionConsumer(ProjectionWriter writer) {
        this.writer = writer;
    }

    @KafkaListener(topics = "ledger.account.v1", groupId = "ledger-projection")
    public void onAccountCreated(String message) {
        try {
            JsonNode event = mapper.readTree(message);
            String accountId   = event.get("accountId").asText();
            String accountType = event.get("accountType").asText();
            String displayName = event.has("displayName") ? event.get("displayName").asText() : "";
            String ownerId     = event.has("ownerId") && !event.get("ownerId").isNull() ? event.get("ownerId").asText() : null;
            String status      = event.get("status").asText();
            LocalDateTime createdAt = toLocalDateTime(event.get("createdAt"));

            writer.writeAccount(accountId, accountType, displayName, ownerId, status, createdAt);
        } catch (Exception e) {
            log.error("Failed to project account event", e);
        }
    }

    /**
     * Batch listener: one whole poll batch per invocation (see {@code batchFactory}
     * in ProjectionConfig). Parses each message and hands the batch to the writer,
     * which persists it in a single transaction.
     */
    @KafkaListener(topics = "ledger.balance.change.v1", groupId = "ledger-projection",
            containerFactory = "batchFactory")
    public void onBalanceChange(List<String> messages) {
        if (messages == null || messages.isEmpty()) return;
        var parsed = new ArrayList<ProjectionWriter.BalanceEvent>(messages.size());
        for (String m : messages) {
            ProjectionWriter.BalanceEvent pe = parseBalanceEvent(m);
            if (pe != null) parsed.add(pe);
        }
        writer.writeBalanceBatch(parsed);
    }

    private ProjectionWriter.BalanceEvent parseBalanceEvent(String message) {
        try {
            JsonNode event = mapper.readTree(message);
            return new ProjectionWriter.BalanceEvent(
                    event.has("eventId") ? event.get("eventId").asText() : "",
                    event.get("journalId").asText(),
                    event.get("journalLineId").asText(),
                    event.get("requestId").asText(),
                    event.get("commandType").asText(),
                    event.get("accountId").asText(),
                    event.get("balanceType").asText(),
                    event.has("position") ? event.get("position").asText() : "CURRENT",
                    event.get("currency").asText(),
                    event.get("entryType").asText(),
                    toBigDecimal(event.get("amount")),
                    toBigDecimal(event.get("postBalance")),
                    event.get("accountSeq").asLong(),
                    event.has("businessEventRef") ? event.get("businessEventRef").asText() : "",
                    toLocalDate(event.get("valueDate")),
                    event.has("configVersion") ? event.get("configVersion").asInt() : 1,
                    event.has("preBalance") ? toBigDecimal(event.get("preBalance")) : BigDecimal.ZERO);
        } catch (Exception e) {
            log.error("Failed to parse balance event", e);
            return null;
        }
    }

    private static BigDecimal toBigDecimal(JsonNode node) {
        if (node == null) return BigDecimal.ZERO;
        if (node.isNumber()) return node.decimalValue();
        return new BigDecimal(node.asText());
    }

    private static LocalDate toLocalDate(JsonNode node) {
        if (node == null) return LocalDate.now();
        if (node.isArray() && node.size() >= 3) {
            return LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
        }
        return LocalDate.parse(node.asText());
    }

    private static LocalDateTime toLocalDateTime(JsonNode node) {
        if (node == null) return LocalDateTime.now();
        if (node.isArray() && node.size() == 2) {
            return java.time.Instant.ofEpochSecond(node.get(0).asLong(), node.get(1).asLong())
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        String text = node.asText();
        if (text.endsWith("Z")) {
            return java.time.Instant.parse(text).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        if (text.contains("T")) {
            return LocalDateTime.parse(text);
        }
        return LocalDateTime.of(toLocalDate(node), java.time.LocalTime.MIN);
    }
}
