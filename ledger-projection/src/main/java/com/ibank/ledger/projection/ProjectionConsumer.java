package com.ibank.ledger.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ibank.ledger.dao.mapper.JournalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class ProjectionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProjectionConsumer.class);
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final JournalMapper journalMapper;

    public ProjectionConsumer(JournalMapper journalMapper) {
        this.journalMapper = journalMapper;
    }

    @KafkaListener(topics = "ledger.balance.change.v1", groupId = "ledger-projection")
    public void onBalanceChange(String message) {
        try {
            JsonNode event = mapper.readTree(message);

            String journalId      = event.get("journalId").asText();
            String journalLineId  = event.get("journalLineId").asText();
            String requestId      = event.get("requestId").asText();
            String commandType    = event.get("commandType").asText();
            String accountId      = event.get("accountId").asText();
            String balanceType    = event.get("balanceType").asText();
            String currency       = event.get("currency").asText();
            String entryType      = event.get("entryType").asText();
            BigDecimal amount     = toBigDecimal(event.get("amount"));
            BigDecimal preBalance = toBigDecimal(event.get("preBalance"));
            BigDecimal postBalance = toBigDecimal(event.get("postBalance"));
            long accountSeq       = event.get("accountSeq").asLong();
            String businessRef    = event.has("businessEventRef") ? event.get("businessEventRef").asText() : "";
            LocalDate valueDate   = toLocalDate(event.get("valueDate"));

            // Idempotent journal insert
            try {
                journalMapper.insertJournal(
                        journalId,
                        "REVERSAL".equals(commandType) ? "REVERSAL" : "NORMAL",
                        requestId, commandType, businessRef,
                        valueDate, "CONFIRMED", false, LocalDateTime.now());
            } catch (Exception e) {
                log.debug("Journal {} already exists", journalId);
            }

            // Idempotent journal line insert
            try {
                journalMapper.insertJournalLine(
                        journalLineId, journalId, "",
                        accountId, balanceType, currency, entryType,
                        amount, preBalance, postBalance, 1, LocalDateTime.now());
            } catch (Exception e) {
                log.debug("JournalLine {} already exists", journalLineId);
            }

            log.info("Projected: {} {} {} {} seq={}", journalId, accountId, entryType, amount, accountSeq);

        } catch (Exception e) {
            log.error("Failed to project event: {}", message, e);
        }
    }

    private static BigDecimal toBigDecimal(JsonNode node) {
        if (node == null) return BigDecimal.ZERO;
        if (node.isNumber()) return node.decimalValue();
        return new BigDecimal(node.asText());
    }

    private static LocalDate toLocalDate(JsonNode node) {
        if (node == null) return LocalDate.now();
        // Jackson serializes LocalDate as [2026,5,18]
        if (node.isArray() && node.size() >= 3) {
            return LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
        }
        return LocalDate.parse(node.asText());
    }
}
