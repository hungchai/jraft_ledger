package com.example.ledger.repository;

import com.example.ledger.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    
    Account findByAccountNumber(String accountNumber);
    
    List<Account> findByAccountType(Account.AccountType accountType);
    
    @Query("SELECT a FROM Account a WHERE a.accountNumber IN :accountNumbers")
    List<Account> findByAccountNumbers(@Param("accountNumbers") List<String> accountNumbers);
    
    boolean existsByAccountNumber(String accountNumber);
} 