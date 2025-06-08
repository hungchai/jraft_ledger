package com.example.ledger.repository;

import com.example.ledger.model.JournalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {
    
    List<JournalEntry> findByTransactionId(Long transactionId);
    
    List<JournalEntry> findByAccountId(Long accountId);
} 