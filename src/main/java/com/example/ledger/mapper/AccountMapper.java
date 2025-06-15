package com.example.ledger.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ledger.model.Account;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {
    
    /**
     * 根據帳戶號碼查詢帳戶
     */
    @Select("SELECT * FROM account WHERE account_number = #{accountNumber} AND deleted = 0")
    Account findByAccountNumber(@Param("accountNumber") String accountNumber);
    
    /**
     * 根據帳戶類型查詢帳戶列表
     */
    @Select("SELECT * FROM account WHERE account_type = #{accountType} AND deleted = 0")
    List<Account> findByAccountType(@Param("accountType") Account.AccountType accountType);
    
    /**
     * 查詢餘額大於指定金額的帳戶
     */
    @Select("SELECT * FROM account WHERE balance > #{amount} AND deleted = 0")
    List<Account> findByBalanceGreaterThan(@Param("amount") BigDecimal amount);
    
    /**
     * 更新帳戶餘額
     */
    @Update("UPDATE account SET balance = #{balance}, version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND deleted = 0")
    int updateBalance(@Param("id") Long id, @Param("balance") BigDecimal balance, @Param("version") Long version);

    @Update("UPDATE account SET balance = #{balance} WHERE account_id = #{accountId}")
    int updateBalanceByAccountId(@Param("accountId") String accountId, @Param("balance") BigDecimal balance);
} 