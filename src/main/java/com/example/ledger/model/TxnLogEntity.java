package com.example.ledger.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("transaction_log")
public class TxnLogEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String fromAccount;
    private String toAccount;
    private String amount;
    private LocalDateTime createdAt;
}
