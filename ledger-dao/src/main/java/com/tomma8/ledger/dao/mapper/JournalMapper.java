package com.tomma8.ledger.dao.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface JournalMapper {

    // ============================================================
    // journal (header)
    // ============================================================

    @Insert("<script>" +
            "INSERT INTO journal (id, journal_id, journal_type, request_id, business_event_type, business_event_ref, value_date, status, cross_period, created_at) " +
            "VALUES (#{id}, #{journalId}, #{journalType}, #{requestId}, #{businessEventType}, #{businessEventRef}, #{valueDate}, #{status}, #{crossPeriod}, #{createdAt})" +
            "</script>")
    int insertJournal(@Param("id") long id,
                      @Param("journalId") String journalId,
                      @Param("journalType") String journalType,
                      @Param("requestId") String requestId,
                      @Param("businessEventType") String businessEventType,
                      @Param("businessEventRef") String businessEventRef,
                      @Param("valueDate") LocalDate valueDate,
                      @Param("status") String status,
                      @Param("crossPeriod") boolean crossPeriod,
                      @Param("createdAt") LocalDateTime createdAt);

    @Select("SELECT id FROM journal WHERE journal_id = #{journalId}")
    Long findIdByJournalId(@Param("journalId") String journalId);

    @Select("SELECT id, journal_id, journal_type, request_id, business_event_type, business_event_ref, value_date, status, cross_period, created_at " +
            "FROM journal WHERE journal_id = #{journalId}")
    Map<String, Object> findById(@Param("journalId") String journalId);

    @Select("SELECT id, journal_id, journal_type, request_id, business_event_type, business_event_ref, value_date, status, cross_period, created_at " +
            "FROM journal WHERE request_id = #{requestId} LIMIT 1")
    Map<String, Object> findJournalByRequestId(@Param("requestId") String requestId);

    @Select("SELECT id, journal_id, journal_type, request_id, business_event_type, business_event_ref, value_date, status, cross_period, created_at " +
            "FROM journal ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String, Object>> findAll(@Param("offset") int offset, @Param("size") int size);

    // ============================================================
    // journal_line
    // ============================================================

    @Insert("<script>" +
            "INSERT INTO journal_line (" +
            "  id, journal_id, account_id, account_balance_id, " +
            "  journal_line_id, journal_journal_id, account_account_id, " +
            "  leg_id, balance_type, position, currency, entry_type, " +
            "  amount, balance_before, balance_after, config_version, created_at" +
            ") VALUES (" +
            "  #{id}, #{journalId}, #{accountId}, #{accountBalanceId}, " +
            "  #{journalLineId}, #{journalJournalId}, #{accountAccountId}, " +
            "  #{legId}, #{balanceType}, #{position}, #{currency}, #{entryType}, " +
            "  #{amount}, #{balanceBefore}, #{balanceAfter}, #{configVersion}, #{createdAt}" +
            ")" +
            "</script>")
    int insertJournalLine(@Param("id") long id,
                          @Param("journalId") long journalId,
                          @Param("accountId") long accountId,
                          @Param("accountBalanceId") long accountBalanceId,
                          @Param("journalLineId") String journalLineId,
                          @Param("journalJournalId") String journalJournalId,
                          @Param("accountAccountId") String accountAccountId,
                          @Param("legId") String legId,
                          @Param("balanceType") String balanceType,
                          @Param("position") String position,
                          @Param("currency") String currency,
                          @Param("entryType") String entryType,
                          @Param("amount") BigDecimal amount,
                          @Param("balanceBefore") BigDecimal balanceBefore,
                          @Param("balanceAfter") BigDecimal balanceAfter,
                          @Param("configVersion") int configVersion,
                          @Param("createdAt") LocalDateTime createdAt);

    @Select("SELECT id, journal_id, account_id, account_balance_id, " +
            "  journal_line_id, journal_journal_id, account_account_id, " +
            "  leg_id, balance_type, position, currency, entry_type, " +
            "  amount, balance_before, balance_after, config_version, created_at " +
            "FROM journal_line WHERE journal_journal_id = #{journalJournalId}")
    List<Map<String, Object>> findLinesByJournalId(@Param("journalJournalId") String journalJournalId);

    @Select("SELECT jl.* FROM journal_line jl " +
            "WHERE jl.account_account_id = #{accountAccountId} " +
            "ORDER BY jl.created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String, Object>> findLinesByAccount(@Param("accountAccountId") String accountAccountId,
                                                   @Param("offset") int offset,
                                                   @Param("size") int size);

    @Select("SELECT j.id, j.journal_id, j.journal_type, j.request_id, j.business_event_type, j.business_event_ref, " +
            "  j.value_date, j.status, j.cross_period, j.created_at " +
            "FROM journal j " +
            "INNER JOIN journal_line jl ON j.id = jl.journal_id " +
            "WHERE jl.account_account_id = #{accountAccountId} " +
            "ORDER BY j.created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String, Object>> findJournalsByAccount(@Param("accountAccountId") String accountAccountId,
                                                     @Param("offset") int offset,
                                                     @Param("size") int size);

    @Select("SELECT id FROM journal_line WHERE journal_line_id = #{journalLineId}")
    Long findIdByJournalLineId(@Param("journalLineId") String journalLineId);
}
