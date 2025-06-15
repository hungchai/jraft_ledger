package com.example.ledger.config;

import com.example.ledger.model.Account;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Custom MyBatis Type Handler for Account.AccountType enum
 * Handles conversion between database string values and enum constants
 */
@MappedTypes(Account.AccountType.class)
public class AccountTypeHandler extends BaseTypeHandler<Account.AccountType> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Account.AccountType parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.getValue());
    }

    @Override
    public Account.AccountType getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return value == null ? null : Account.AccountType.fromValue(value);
    }

    @Override
    public Account.AccountType getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String value = rs.getString(columnIndex);
        return value == null ? null : Account.AccountType.fromValue(value);
    }

    @Override
    public Account.AccountType getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String value = cs.getString(columnIndex);
        return value == null ? null : Account.AccountType.fromValue(value);
    }
} 