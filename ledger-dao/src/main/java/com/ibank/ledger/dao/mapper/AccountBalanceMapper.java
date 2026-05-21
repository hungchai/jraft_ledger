package com.ibank.ledger.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountBalanceMapper {

    @Insert("INSERT INTO account_balance (account_id, balance_type, currency, amount, account_seq, last_journal_id, updated_at) " +
            "VALUES (#{accountId}, #{balanceType}, #{currency}, #{amount}, #{accountSeq}, #{lastJournalId}, #{updatedAt}) " +
            "ON DUPLICATE KEY UPDATE amount = VALUES(amount), account_seq = VALUES(account_seq), last_journal_id = VALUES(last_journal_id), updated_at = VALUES(updated_at)")
    int upsertBalance(@Param("accountId") String accountId,
                      @Param("balanceType") String balanceType,
                      @Param("currency") String currency,
                      @Param("amount") java.math.BigDecimal amount,
                      @Param("accountSeq") long accountSeq,
                      @Param("lastJournalId") String lastJournalId,
                      @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
