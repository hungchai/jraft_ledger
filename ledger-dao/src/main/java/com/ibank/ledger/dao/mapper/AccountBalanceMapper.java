package com.ibank.ledger.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AccountBalanceMapper {

    @Insert("INSERT INTO account_balance (account_id, balance_type, currency, amount, account_seq, last_journal_id) " +
            "VALUES (#{accountId}, #{balanceType}, #{currency}, #{amount}, #{accountSeq}, #{lastJournalId}) " +
            "ON DUPLICATE KEY UPDATE amount = VALUES(amount), account_seq = VALUES(account_seq), last_journal_id = VALUES(last_journal_id)")
    int upsertBalance(@Param("accountId") String accountId,
                      @Param("balanceType") String balanceType,
                      @Param("currency") String currency,
                      @Param("amount") java.math.BigDecimal amount,
                      @Param("accountSeq") long accountSeq,
                      @Param("lastJournalId") String lastJournalId);

    @Select("SELECT account_id, balance_type, currency, amount, frozen_amount, locked_amount, account_seq, last_journal_id " +
            "FROM account_balance WHERE account_id = #{accountId}")
    List<Map<String, Object>> findByAccountId(@Param("accountId") String accountId);

    @Select("SELECT account_id, balance_type, currency, amount, frozen_amount, locked_amount, account_seq, last_journal_id " +
            "FROM account_balance WHERE account_id = #{accountId} AND balance_type = #{balanceType} AND currency = #{currency}")
    Map<String, Object> findByKey(@Param("accountId") String accountId,
                                  @Param("balanceType") String balanceType,
                                  @Param("currency") String currency);
}
