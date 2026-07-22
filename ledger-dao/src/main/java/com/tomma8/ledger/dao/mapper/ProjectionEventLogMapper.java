package com.tomma8.ledger.dao.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ProjectionEventLogMapper {

    /**
     * Insert an event record. UK on (account_account_id, balance_type, currency, account_seq)
     * rejects exact Kafka duplicates. Stale events (lower account_seq) are caught by
     * the account_balance seq guard, not here.
     */
    @Insert("INSERT INTO projection_event_log (account_account_id, balance_type, currency, account_seq, journal_line_id, journal_journal_id, event_id, status) " +
            "VALUES (#{accountAccountId}, #{balanceType}, #{currency}, #{accountSeq}, #{journalLineId}, #{journalJournalId}, #{eventId}, #{status}) ON CONFLICT DO NOTHING")
    int insertEvent(@Param("accountAccountId") String accountAccountId,
                    @Param("balanceType") String balanceType,
                    @Param("currency") String currency,
                    @Param("accountSeq") long accountSeq,
                    @Param("journalLineId") String journalLineId,
                    @Param("journalJournalId") String journalJournalId,
                    @Param("eventId") String eventId,
                    @Param("status") String status);

    /**
     * Update status for an already-logged event (e.g., SKIPPED_STALE after seq check).
     */
    @Update("UPDATE projection_event_log SET status = #{status} " +
            "WHERE account_account_id = #{accountAccountId} AND balance_type = #{balanceType} " +
            "AND currency = #{currency} AND account_seq = #{accountSeq}")
    int updateStatus(@Param("accountAccountId") String accountAccountId,
                     @Param("balanceType") String balanceType,
                     @Param("currency") String currency,
                     @Param("accountSeq") long accountSeq,
                     @Param("status") String status);

    @Select("SELECT MAX(account_seq) FROM projection_event_log " +
            "WHERE account_account_id = #{accountAccountId} AND balance_type = #{balanceType} AND currency = #{currency}")
    Long maxAccountSeq(@Param("accountAccountId") String accountAccountId,
                       @Param("balanceType") String balanceType,
                       @Param("currency") String currency);

    @Insert("<script>" +
            "INSERT INTO projection_event_log (" +
            "  account_account_id, balance_type, currency, account_seq, journal_line_id, journal_journal_id, event_id, status" +
            ") VALUES " +
            "<foreach collection='rows' item='r' separator=','>" +
            "(#{r.accountAccountId}, #{r.balanceType}, #{r.currency}, #{r.accountSeq}, " +
            " #{r.journalLineId}, #{r.journalJournalId}, #{r.eventId}, 'APPLIED')" +
            "</foreach> ON CONFLICT DO NOTHING" +
            "</script>")
    int batchInsertEvents(@Param("rows") List<EventLogBatchRow> rows);

    record EventLogBatchRow(
            String accountAccountId,
            String balanceType,
            String currency,
            long accountSeq,
            String journalLineId,
            String journalJournalId,
            String eventId) {}
}
