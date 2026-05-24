package com.tomma8.ledger.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tomma8.ledger.dao.mapper.AccountBalanceMapper;
import com.tomma8.ledger.dao.mapper.AccountMapper;
import com.tomma8.ledger.dao.mapper.BalanceTypeMapper;
import com.tomma8.ledger.dao.mapper.JournalMapper;
import com.tomma8.ledger.utils.SnowflakeIdGenerator;
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
    private final AccountMapper accountMapper;
    private final AccountBalanceMapper accountBalanceMapper;
    private final BalanceTypeMapper balanceTypeMapper;
    private final SnowflakeIdGenerator idGenerator;

    public ProjectionConsumer(JournalMapper journalMapper,
                              AccountMapper accountMapper,
                              AccountBalanceMapper accountBalanceMapper,
                              BalanceTypeMapper balanceTypeMapper) {
        this.journalMapper = journalMapper;
        this.accountMapper = accountMapper;
        this.accountBalanceMapper = accountBalanceMapper;
        this.balanceTypeMapper = balanceTypeMapper;
        this.idGenerator = SnowflakeIdGenerator.forWorker(SnowflakeIdGenerator.deriveWorkerId());
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

            accountMapper.upsertAccount(accountId, accountType, displayName, ownerId, status, createdAt);
            log.info("Projected account: {} type={} status={}", accountId, accountType, status);

        } catch (Exception e) {
            log.error("Failed to project account event: {}", message, e);
        }
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
            String position       = event.has("position") ? event.get("position").asText() : "CURRENT";
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
                        idGenerator.nextId(),
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
                        idGenerator.nextId(),
                        journalLineId, journalId, "",
                        accountId, balanceType, position, currency, entryType,
                        amount, preBalance, postBalance, 1, LocalDateTime.now());
            } catch (Exception e) {
                log.debug("JournalLine {} already exists", journalLineId);
            }

            // Upsert account balance
            try {
                accountBalanceMapper.upsertBalance(
                        accountId, balanceType, position, currency, postBalance, accountSeq, journalId);
            } catch (Exception e) {
                log.error("Failed to upsert balance for {} {} {} {}", accountId, balanceType, position, currency, e);
            }

            log.info("Projected: {} {} {} {} seq={} balance={}", journalId, accountId, entryType, amount, accountSeq, postBalance);

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
        if (node.isArray() && node.size() >= 3) {
            return LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
        }
        return LocalDate.parse(node.asText());
    }

    private static LocalDateTime toLocalDateTime(JsonNode node) {
        if (node == null) return LocalDateTime.now();
        if (node.isArray() && node.size() == 2) {
            // Jackson serializes Instant as [epochSecond, nano]
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
