package com.example.ledger.model;

import com.example.ledger.entity.Transaction;
import lombok.Data;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
public class JournalEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private String description;
    private LocalDateTime timestamp;

    public JournalEntry() {}

    public JournalEntry(Transaction transaction, Account account, BigDecimal debitAmount, BigDecimal creditAmount, String description) {
        this.transaction = transaction;
        this.account = account;
        this.debitAmount = debitAmount;
        this.creditAmount = creditAmount;
        this.description = description;
        this.timestamp = LocalDateTime.now();
    }
} 