package com.tomma8.ledger.dao.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

@Mapper
public interface AccountMapper {

    @Insert("INSERT INTO account (account_id, account_type, display_name, owner_id, status, created_at) " +
            "VALUES (#{accountId}, #{accountType}, #{displayName}, #{ownerId}, #{status}, #{createdAt}) " +
            "ON CONFLICT (account_id) DO UPDATE SET " +
            "  account_type = EXCLUDED.account_type, " +
            "  status = EXCLUDED.status, " +
            "  display_name = EXCLUDED.display_name, " +
            "  updated_at = CURRENT_TIMESTAMP")
    int upsertAccount(@Param("accountId") String accountId,
                      @Param("accountType") String accountType,
                      @Param("displayName") String displayName,
                      @Param("ownerId") String ownerId,
                      @Param("status") String status,
                      @Param("createdAt") LocalDateTime createdAt);

    /**
     * Returns the surrogate id (account.id) for the given business key.
     * Call after upsertAccount() to get the stable surrogate id.
     */
    @Select("SELECT id FROM account WHERE account_id = #{accountId}")
    Long findIdByAccountId(@Param("accountId") String accountId);

    @Select("SELECT id, account_id, account_type, display_name, owner_id, status, created_at, updated_at " +
            "FROM account WHERE account_id = #{accountId}")
    java.util.Map<String, Object> findByAccountId(@Param("accountId") String accountId);
}
