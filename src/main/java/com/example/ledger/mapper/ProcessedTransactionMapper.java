package com.example.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.model.ProcessedTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface ProcessedTransactionMapper extends BaseMapper<ProcessedTransaction> {
    
    /**
     * 根据交易ID查询交易
     */
    @Select("SELECT * FROM processed_transaction WHERE transaction_id = #{transactionId} AND deleted = 0")
    ProcessedTransaction findByTransactionId(@Param("transactionId") String transactionId);
    
    /**
     * 根据账户ID查询相关交易（转出）
     */
    @Select("SELECT * FROM processed_transaction WHERE from_account_id = #{accountId} AND deleted = 0 ORDER BY processed_at DESC")
    List<ProcessedTransaction> findByFromAccountId(@Param("accountId") String accountId);
    
    /**
     * 根据账户ID查询相关交易（转入）
     */
    @Select("SELECT * FROM processed_transaction WHERE to_account_id = #{accountId} AND deleted = 0 ORDER BY processed_at DESC")
    List<ProcessedTransaction> findByToAccountId(@Param("accountId") String accountId);
    
    /**
     * 查询账户的所有相关交易（转入和转出）
     */
    @Select("SELECT * FROM processed_transaction WHERE (from_account_id = #{accountId} OR to_account_id = #{accountId}) AND deleted = 0 ORDER BY processed_at DESC")
    List<ProcessedTransaction> findByAccountId(@Param("accountId") String accountId);
    
    /**
     * 查询金额大于指定值的交易
     */
    @Select("SELECT * FROM processed_transaction WHERE amount > #{amount} AND deleted = 0 ORDER BY processed_at DESC")
    List<ProcessedTransaction> findByAmountGreaterThan(@Param("amount") BigDecimal amount);
    
    /**
     * 根据状态查询交易
     */
    @Select("SELECT * FROM processed_transaction WHERE status = #{status} AND deleted = 0 ORDER BY processed_at DESC")
    List<ProcessedTransaction> findByStatus(@Param("status") String status);
    
    /**
     * 查询指定时间范围内的交易
     */
    @Select("SELECT * FROM processed_transaction WHERE processed_at BETWEEN #{startTime} AND #{endTime} AND deleted = 0 ORDER BY processed_at DESC")
    List<ProcessedTransaction> findByTimeRange(@Param("startTime") String startTime, @Param("endTime") String endTime);
    
    /**
     * 根据幂等性ID查询交易
     */
    @Select("SELECT * FROM processed_transaction WHERE idempotent_id = #{idempotentId} AND deleted = 0")
    ProcessedTransaction findByIdempotentId(@Param("idempotentId") String idempotentId);
} 