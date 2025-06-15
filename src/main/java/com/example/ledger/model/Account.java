package com.example.ledger.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("account")
public class Account {
    @TableId
    private String accountId;

    private String userId;
    private AccountType accountType;
    private BigDecimal balance;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // 账户类型枚举
    public enum AccountType {
        BROKERAGE("brokerage"),
        EXCHANGE("exchange"), 
        AVAILABLE("available");
        
        private final String value;
        
        AccountType(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
        
        public static AccountType fromValue(String value) {
            for (AccountType type : AccountType.values()) {
                if (type.value.equals(value)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown account type: " + value);
        }
    }
    
    // 生成账户ID的工具方法
    public static String generateAccountId(String userId, AccountType accountType) {
        return userId + ":" + accountType.getValue();
    }
}
