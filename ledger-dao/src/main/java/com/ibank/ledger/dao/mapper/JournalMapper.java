package com.ibank.ledger.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface JournalMapper {

    @Insert("INSERT INTO journal (journal_id, journal_type, request_id, business_event_type, business_event_ref, value_date, status, cross_period, created_at) " +
            "VALUES (#{journalId}, #{journalType}, #{requestId}, #{businessEventType}, #{businessEventRef}, #{valueDate}, #{status}, #{crossPeriod}, #{createdAt})")
    int insertJournal(@Param("journalId") String journalId,
                      @Param("journalType") String journalType,
                      @Param("requestId") String requestId,
                      @Param("businessEventType") String businessEventType,
                      @Param("businessEventRef") String businessEventRef,
                      @Param("valueDate") java.time.LocalDate valueDate,
                      @Param("status") String status,
                      @Param("crossPeriod") boolean crossPeriod,
                      @Param("createdAt") LocalDateTime createdAt);

    @Insert("INSERT INTO journal_line (journal_line_id, journal_id, leg_id, account_id, balance_type, currency, entry_type, amount, balance_before, balance_after, config_version, created_at) " +
            "VALUES (#{journalLineId}, #{journalId}, #{legId}, #{accountId}, #{balanceType}, #{currency}, #{entryType}, #{amount}, #{balanceBefore}, #{balanceAfter}, #{configVersion}, #{createdAt})")
    int insertJournalLine(@Param("journalLineId") String journalLineId,
                          @Param("journalId") String journalId,
                          @Param("legId") String legId,
                          @Param("accountId") String accountId,
                          @Param("balanceType") String balanceType,
                          @Param("currency") String currency,
                          @Param("entryType") String entryType,
                          @Param("amount") java.math.BigDecimal amount,
                          @Param("balanceBefore") java.math.BigDecimal balanceBefore,
                          @Param("balanceAfter") java.math.BigDecimal balanceAfter,
                          @Param("configVersion") int configVersion,
                          @Param("createdAt") LocalDateTime createdAt);

    @Select("SELECT * FROM journal WHERE journal_id = #{journalId}")
    Map<String, Object> findById(@Param("journalId") String journalId);

    @Select("SELECT * FROM journal ORDER BY created_at DESC LIMIT #{size} OFFSET #{offset}")
    List<Map<String, Object>> findAll(@Param("offset") int offset, @Param("size") int size);
}
