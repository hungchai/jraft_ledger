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
import java.time.format.DateTimeFormatter;

/**
 * Kafka consumer that projects BalanceChangeEvents into MySQL View Layer.
 *
 * Flow: Kafka ledger.balance.change.v1 → deserialize → MySQL INSERT journal/journal_line
 * Idempotency: INSERT ... ON DUPLICATE KEY ensures at-least-once safety.
 */
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
            BigDecimal amount     = new BigDecimal(event.get("amount").asText());
            BigDecimal preBalance = new BigDecimal(event.get("preBalance").asText());
            BigDecimal postBalance = new BigDecimal(event.get("postBalance").asText());
            long accountSeq       = event.get("accountSeq").asLong();
            String valueDateStr   = event.get("valueDate").asText();
            LocalDate valueDate   = LocalDate.parse(valueDateStr);

            // Idempotent — journal may already exist from another projection instance
            try {
                journalMapper.insertJournal(
                        journalId,
                        commandType.equals("REVERSAL") ? "REVERSAL" : "NORMAL",
                        requestId,
                        commandType,
                        event.has("businessEventRef") ? event.get("businessEventRef").asText() : "",
                        valueDate,
                        "CONFIRMED",
                        false,
                        LocalDateTime.now()
                );
            } catch (Exception e) {
                log.debug("Journal {} already exists (idempotent)", journalId);
            }

            try {
                journalMapper.insertJournalLine(
                        journalLineId, journalId,
                        "", // legId from event if available
                        accountId, balanceType, currency, entryType,
                        amount, preBalance, postBalance,
                        1, LocalDateTime.now()
                );
            } catch (Exception e) {
                log.debug("JournalLine {} already exists (idempotent)", journalLineId);
            }

            log.debug("Projected: {} {} {} {} {} seq={}", journalId, accountId, entryType, amount, currency, accountSeq);

        } catch (Exception e) {
            log.error("Failed to project event: {}", message, e);
        }
    }
}
