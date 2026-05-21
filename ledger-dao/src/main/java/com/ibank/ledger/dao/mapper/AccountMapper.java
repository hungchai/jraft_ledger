package com.ibank.ledger.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AccountMapper {

    @Insert("INSERT INTO account (account_id, account_type, display_name, owner_id, status, created_at) " +
            "VALUES (#{accountId}, #{accountType}, #{displayName}, #{ownerId}, #{status}, #{createdAt}) " +
            "ON DUPLICATE KEY UPDATE account_type = VALUES(account_type), status = VALUES(status), display_name = VALUES(display_name)")
    int upsertAccount(@Param("accountId") String accountId,
                      @Param("accountType") String accountType,
                      @Param("displayName") String displayName,
                      @Param("ownerId") String ownerId,
                      @Param("status") String status,
                      @Param("createdAt") java.time.LocalDateTime createdAt);
}
