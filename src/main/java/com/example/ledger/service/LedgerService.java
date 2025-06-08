package com.example.ledger.service;

import com.example.ledger.dto.AccountResponse;
import com.example.ledger.dto.TransferRequest;
import com.example.ledger.exception.AccountNotFoundException;
import com.example.ledger.exception.InsufficientFundsException;
import com.example.ledger.exception.InvalidTransferException;
import com.example.ledger.exception.DuplicateTransactionException;
import com.example.ledger.exception.DuplicateAccountException;
import com.example.ledger.model.Account;
import com.example.ledger.entity.Transaction;
import com.example.ledger.model.JournalEntry;
import com.example.ledger.repository.AccountRepository;
import com.example.ledger.repository.JournalEntryRepository;
import com.example.ledger.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class LedgerService {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Transactional
    public void executeTransfer(TransferRequest request) {
        // Check for duplicate reference ID for idempotency
        if (transactionRepository.findByReferenceId(request.getReferenceId()) != null) {
            throw new DuplicateTransactionException("Transaction with this reference ID already exists");
        }

        Account fromAccount = accountRepository.findByAccountNumber(request.getFromAccountNumber());
        if (fromAccount == null) {
            throw new AccountNotFoundException("From account not found");
        }

        Account toAccount = accountRepository.findByAccountNumber(request.getToAccountNumber());
        if (toAccount == null) {
            throw new AccountNotFoundException("To account not found");
        }

        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidTransferException("Amount must be positive");
        }

        if (fromAccount.getBalance().compareTo(request.getAmount()) < 0) {
            throw new InsufficientFundsException("Insufficient funds");
        }

        // Create transaction record
        Transaction transaction = new Transaction();
        transaction.setFromAccount(fromAccount);
        transaction.setToAccount(toAccount);
        transaction.setAmount(request.getAmount());
        transaction.setDescription(request.getDescription());
        transaction.setReferenceId(request.getReferenceId());
        transaction.setTimestamp(LocalDateTime.now());

        // Update account balances
        fromAccount.setBalance(fromAccount.getBalance().subtract(request.getAmount()));
        toAccount.setBalance(toAccount.getBalance().add(request.getAmount()));

        // Save changes
        transactionRepository.save(transaction);
        accountRepository.save(fromAccount);
        accountRepository.save(toAccount);

        // Create journal entries for double-entry accounting
        JournalEntry fromEntry = new JournalEntry(transaction, fromAccount, request.getAmount(), BigDecimal.ZERO, "Transfer to " + toAccount.getAccountNumber());
        JournalEntry toEntry = new JournalEntry(transaction, toAccount, BigDecimal.ZERO, request.getAmount(), "Transfer from " + fromAccount.getAccountNumber());

        journalEntryRepository.save(fromEntry);
        journalEntryRepository.save(toEntry);
    }

    @Transactional
    public void executeBatchTransfer(TransferRequest[] requests) {
        // Process each transfer sequentially within the same transaction
        // This ensures data consistency and avoids optimistic locking issues
        for (TransferRequest request : requests) {
            executeTransfer(request);
        }
    }

    public List<AccountResponse> getAllAccounts() {
        return accountRepository.findAll().stream()
            .map(AccountResponse::fromEntity)
            .collect(Collectors.toList());
    }

    public AccountResponse getAccount(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber);
        if (account == null) {
            throw new AccountNotFoundException("Account not found");
        }
        return AccountResponse.fromEntity(account);
    }

    @Transactional
    public void createAccount(String accountNumber, String accountName, Account.AccountType accountType, BigDecimal initialBalance) {
        // Check for duplicate account number for idempotency
        if (accountRepository.findByAccountNumber(accountNumber) != null) {
            throw new DuplicateAccountException("Account with this number already exists");
        }

        Account account = new Account();
        account.setAccountNumber(accountNumber);
        account.setAccountName(accountName);
        account.setAccountType(accountType);
        account.setBalance(initialBalance);
        accountRepository.save(account);
    }
} 