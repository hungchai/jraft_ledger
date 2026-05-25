package com.tomma8.ledger.dao.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface AccountBalanceMapper {

    /**
     * Ensure an account_balance row exists, returning the surrogate id.
     * On duplicate key: only touch the row by setting LAST_INSERT_ID(id),
     * actual data update comes via applyBalanceChange().
     */
    @Insert("<script>" +
            "INSERT INTO account_balance (account_id, account_account_id, balance_type, currency, amount, frozen_amount, locked_amount, account_seq, last_journal_id) " +
            "VALUES (#{accountId}, #{accountAccountId}, #{balanceType}, #{currency}, " +
            "  <choose>" +
            "    <when test='position == \"FROZEN\"'>0, #{amount}, 0</when>" +
            "    <when test='position == \"LOCKED\"'>0, 0, #{amount}</when>" +
            "    <otherwise>#{amount}, 0, 0</otherwise>" +
            "  </choose>" +
            ", #{accountSeq}, #{lastJournalId}) " +
            "ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)" +
            "</script>")
    int ensureBalanceRow(@Param("accountId") long accountId,
                         @Param("accountAccountId") String accountAccountId,
                         @Param("balanceType") String balanceType,
                         @Param("currency") String currency,
                         @Param("amount") BigDecimal amount,
                         @Param("position") String position,
                         @Param("accountSeq") long accountSeq,
                         @Param("lastJournalId") String lastJournalId);

    /**
     * Apply a balance change with accountSeq ordering guard.
     * Only updates when incoming accountSeq >= stored accountSeq.
     * Returns 1 if applied, 0 if stale (incoming seq < stored).
     */
    @Update("<script>" +
            "UPDATE account_balance SET " +
            "  <choose>" +
            "    <when test='position == \"FROZEN\"'>frozen_amount = #{amount}, amount = 0</when>" +
            "    <when test='position == \"LOCKED\"'>locked_amount = #{amount}, amount = 0</when>" +
            "    <otherwise>amount = #{amount}, frozen_amount = 0, locked_amount = 0</otherwise>" +
            "  </choose>" +
            ", account_seq = #{accountSeq}" +
            ", last_journal_id = #{lastJournalId}" +
            " WHERE id = #{id} AND account_seq &lt;= #{accountSeq}" +
            "</script>")
    int applyBalanceChange(@Param("id") long id,
                           @Param("amount") BigDecimal amount,
                           @Param("position") String position,
                           @Param("accountSeq") long accountSeq,
                           @Param("lastJournalId") String lastJournalId);

    /**
     * Upsert balance with accountSeq guard in a single atomic operation.
     * Uses INSERT ... ON DUPLICATE KEY UPDATE with IF guards.
     * Returns the surrogate id (via LAST_INSERT_ID) on success.
     * If incoming accountSeq < stored, data columns are not updated but id is still returned.
     * Caller should check account_seq after this to determine if change was applied.
     */
    @Insert("<script>" +
            "INSERT INTO account_balance (account_id, account_account_id, balance_type, currency, amount, frozen_amount, locked_amount, account_seq, last_journal_id) " +
            "VALUES (#{accountId}, #{accountAccountId}, #{balanceType}, #{currency}, " +
            "  <choose>" +
            "    <when test='position == \"FROZEN\"'>0, #{amount}, 0</when>" +
            "    <when test='position == \"LOCKED\"'>0, 0, #{amount}</when>" +
            "    <otherwise>#{amount}, 0, 0</otherwise>" +
            "  </choose>" +
            ", #{accountSeq}, #{lastJournalId}) " +
            "ON DUPLICATE KEY UPDATE " +
            "  <choose>" +
            "    <when test='position == \"FROZEN\"'>" +
            "      frozen_amount = IF(#{accountSeq} &gt;= account_seq, VALUES(frozen_amount), frozen_amount)" +
            "    </when>" +
            "    <when test='position == \"LOCKED\"'>" +
            "      locked_amount = IF(#{accountSeq} &gt;= account_seq, VALUES(locked_amount), locked_amount)" +
            "    </when>" +
            "    <otherwise>" +
            "      amount = IF(#{accountSeq} &gt;= account_seq, VALUES(amount), amount)" +
            "    </otherwise>" +
            "  </choose>" +
            ", account_seq = IF(#{accountSeq} &gt;= account_seq, VALUES(account_seq), account_seq)" +
            ", last_journal_id = IF(#{accountSeq} &gt;= account_seq, VALUES(last_journal_id), last_journal_id)" +
            ", id = LAST_INSERT_ID(id)" +
            "</script>")
    int upsertBalance(@Param("accountId") long accountId,
                      @Param("accountAccountId") String accountAccountId,
                      @Param("balanceType") String balanceType,
                      @Param("currency") String currency,
                      @Param("amount") BigDecimal amount,
                      @Param("position") String position,
                      @Param("accountSeq") long accountSeq,
                      @Param("lastJournalId") String lastJournalId);

    @Select("SELECT id, account_id, account_account_id, balance_type, currency, amount, frozen_amount, locked_amount, account_seq, last_journal_id " +
            "FROM account_balance WHERE account_account_id = #{accountAccountId} AND balance_type = #{balanceType} AND currency = #{currency}")
    Map<String, Object> findByKey(@Param("accountAccountId") String accountAccountId,
                                  @Param("balanceType") String balanceType,
                                  @Param("currency") String currency);

    @Select("SELECT id FROM account_balance WHERE account_account_id = #{accountAccountId} AND balance_type = #{balanceType} AND currency = #{currency}")
    Long findIdByKey(@Param("accountAccountId") String accountAccountId,
                     @Param("balanceType") String balanceType,
                     @Param("currency") String currency);

    @Select("SELECT account_seq FROM account_balance WHERE id = #{id}")
    Long getAccountSeq(@Param("id") long id);

    @Select("SELECT id FROM account_balance WHERE account_id = #{accountId}")
    List<Long> findIdsByAccountId(@Param("accountId") long accountId);

    @Select("SELECT id, account_id, account_account_id, balance_type, currency, amount, frozen_amount, locked_amount, account_seq, last_journal_id " +
            "FROM account_balance WHERE account_id = #{accountId}")
    List<Map<String, Object>> findByAccountId(@Param("accountId") long accountId);

    /**
     * Find all balance rows for an account by business key (account_account_id).
     * Used by projection query controller for direct business-key lookups.
     */
    @Select("SELECT id, account_id, account_account_id, balance_type, currency, amount, frozen_amount, locked_amount, account_seq, last_journal_id " +
            "FROM account_balance WHERE account_account_id = #{accountAccountId}")
    List<Map<String, Object>> findByAccountAccountId(@Param("accountAccountId") String accountAccountId);
}
