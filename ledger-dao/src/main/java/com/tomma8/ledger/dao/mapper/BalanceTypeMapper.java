package com.tomma8.ledger.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BalanceTypeMapper {

    @Insert("INSERT INTO balance_type_registry (type_code, display_name, description, category, status, sign_convention, allow_negative, negative_semantics, zero_floor_enforce, currency_scope, config_version, created_by, created_at, change_reason) " +
            "VALUES (#{typeCode}, #{displayName}, #{description}, #{category}, #{status}, #{signConvention}, #{allowNegative}, #{negativeSemantics}, #{zeroFloorEnforce}, #{currencyScope}, #{configVersion}, #{createdBy}, #{createdAt}, #{changeReason}) " +
            "ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), description = VALUES(description), sign_convention = VALUES(sign_convention), allow_negative = VALUES(allow_negative), negative_semantics = VALUES(negative_semantics), zero_floor_enforce = VALUES(zero_floor_enforce), currency_scope = VALUES(currency_scope), config_version = VALUES(config_version), last_modified_by = VALUES(created_by), last_modified_at = VALUES(created_at), change_reason = VALUES(change_reason)")
    int upsertType(@Param("typeCode") String typeCode,
                   @Param("displayName") String displayName,
                   @Param("description") String description,
                   @Param("category") String category,
                   @Param("status") String status,
                   @Param("signConvention") String signConvention,
                   @Param("allowNegative") boolean allowNegative,
                   @Param("negativeSemantics") String negativeSemantics,
                   @Param("zeroFloorEnforce") boolean zeroFloorEnforce,
                   @Param("currencyScope") String currencyScope,
                   @Param("configVersion") int configVersion,
                   @Param("createdBy") String createdBy,
                   @Param("createdAt") java.time.LocalDateTime createdAt,
                   @Param("changeReason") String changeReason);
}
