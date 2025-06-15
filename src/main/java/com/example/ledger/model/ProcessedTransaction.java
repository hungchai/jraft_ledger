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
@TableName("processed_transaction")
public class ProcessedTransaction {
    @TableId
    private String transactionId;
    
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private String description;
    private String idempotentId;
    private LocalDateTime processedAt;
    private String status;
} 