package com.tomma8.ledger.dao.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BalanceTypeMapper {

    @Insert("INSERT INTO balance_type_registry (type_code, display_name, description, category, status, sign_convention, allow_negative, negative_semantics, zero_floor_enforce, currency_scope, config_version, created_by, created_at, change_reason) " +
            "VALUES (#{typeCode}, CAST(#{displayName} AS JSONB), #{description}, #{category}, #{status}, #{signConvention}, #{allowNegative}, #{negativeSemantics}, #{zeroFloorEnforce}, #{currencyScope}, #{configVersion}, #{createdBy}, #{createdAt}, #{changeReason}) " +
            "ON CONFLICT (type_code) DO UPDATE SET display_name = EXCLUDED.display_name, description = EXCLUDED.description, sign_convention = EXCLUDED.sign_convention, allow_negative = EXCLUDED.allow_negative, negative_semantics = EXCLUDED.negative_semantics, zero_floor_enforce = EXCLUDED.zero_floor_enforce, currency_scope = EXCLUDED.currency_scope, config_version = EXCLUDED.config_version, last_modified_by = EXCLUDED.created_by, last_modified_at = EXCLUDED.created_at, change_reason = EXCLUDED.change_reason, updated_at = CURRENT_TIMESTAMP")
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
