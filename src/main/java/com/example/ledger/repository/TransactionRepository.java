package com.example.ledger.repository;

import com.example.ledger.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByFromAccountAccountNumberOrToAccountAccountNumber(String fromAccountNumber, String toAccountNumber);
    Transaction findByReferenceId(String referenceId);
} 