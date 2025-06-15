package com.example.ledger.service;

import com.example.ledger.controller.TransferController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class IdempotencyService {
    
    private final ConcurrentHashMap<String, IdempotencyResult> idempotencyCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
    
    // Cache TTL in minutes
    private static final long CACHE_TTL_MINUTES = 60;
    
    public IdempotencyService() {
        // Schedule cleanup task to run every 30 minutes
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredEntries, 30, 30, TimeUnit.MINUTES);
    }
    
    /**
     * Generate idempotency key from request content
     */
    public String generateIdempotencyKey(TransferController.SingleTransferRequest request) {
        try {
            String content = String.format("%s:%s:%s:%s:%s:%s",
                request.getFromUserId(),
                request.getFromType().getValue(),
                request.getToUserId(), 
                request.getToType().getValue(),
                request.getAmount().toString(),
                request.getDescription() != null ? request.getDescription() : "");
            
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content.getBytes());
            StringBuilder hexString = new StringBuilder();
            
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            
            return "auto-" + hexString.toString().substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to generate idempotency key", e);
            return "auto-" + System.currentTimeMillis();
        }
    }
    
    /**
     * Check if request with this idempotency key has been processed
     */
    public IdempotencyResult checkIdempotency(String idempotencyKey) {
        IdempotencyResult result = idempotencyCache.get(idempotencyKey);
        if (result != null && !result.isExpired()) {
            return result;
        }
        return null;
    }
    
    /**
     * Mark request as being processed
     */
    public void markProcessing(String idempotencyKey) {
        IdempotencyResult result = new IdempotencyResult();
        result.setIdempotencyKey(idempotencyKey);
        result.setProcessing(true);
        result.setCreatedAt(LocalDateTime.now());
        idempotencyCache.put(idempotencyKey, result);
    }
    
    /**
     * Store the result of processing
     */
    public void storeResult(String idempotencyKey, boolean success, String message, int statusCode) {
        IdempotencyResult result = idempotencyCache.get(idempotencyKey);
        if (result == null) {
            result = new IdempotencyResult();
            result.setIdempotencyKey(idempotencyKey);
            result.setCreatedAt(LocalDateTime.now());
        }
        
        result.setProcessing(false);
        result.setSuccess(success);
        result.setMessage(message);
        result.setStatusCode(statusCode);
        result.setCompletedAt(LocalDateTime.now());
        
        idempotencyCache.put(idempotencyKey, result);
        log.info("Stored idempotency result for key: {}, success: {}", idempotencyKey, success);
    }
    
    /**
     * Clean up expired entries from cache
     */
    private void cleanupExpiredEntries() {
        LocalDateTime cutoff = LocalDateTime.now().minus(CACHE_TTL_MINUTES, ChronoUnit.MINUTES);
        int removedCount = 0;
        
        for (String key : idempotencyCache.keySet()) {
            IdempotencyResult result = idempotencyCache.get(key);
            if (result != null && result.getCreatedAt().isBefore(cutoff)) {
                idempotencyCache.remove(key);
                removedCount++;
            }
        }
        
        if (removedCount > 0) {
            log.info("Cleaned up {} expired idempotency entries", removedCount);
        }
    }
    
    /**
     * Get cache statistics
     */
    public IdempotencyCacheStats getCacheStats() {
        int totalEntries = idempotencyCache.size();
        int processingEntries = 0;
        int completedEntries = 0;
        
        for (IdempotencyResult result : idempotencyCache.values()) {
            if (result.isProcessing()) {
                processingEntries++;
            } else {
                completedEntries++;
            }
        }
        
        return new IdempotencyCacheStats(totalEntries, processingEntries, completedEntries);
    }
    
    // DTOs
    public static class IdempotencyResult {
        private String idempotencyKey;
        private boolean processing;
        private boolean success;
        private String message;
        private int statusCode;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
        
        public boolean isExpired() {
            return createdAt != null && 
                   createdAt.isBefore(LocalDateTime.now().minus(CACHE_TTL_MINUTES, ChronoUnit.MINUTES));
        }
        
        // Getters and Setters
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
        
        public boolean isProcessing() { return processing; }
        public void setProcessing(boolean processing) { this.processing = processing; }
        
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
        
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        
        public int getStatusCode() { return statusCode; }
        public void setStatusCode(int statusCode) { this.statusCode = statusCode; }
        
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
        
        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    }
    
    public static class IdempotencyCacheStats {
        private final int totalEntries;
        private final int processingEntries;
        private final int completedEntries;
        
        public IdempotencyCacheStats(int totalEntries, int processingEntries, int completedEntries) {
            this.totalEntries = totalEntries;
            this.processingEntries = processingEntries;
            this.completedEntries = completedEntries;
        }
        
        public int getTotalEntries() { return totalEntries; }
        public int getProcessingEntries() { return processingEntries; }
        public int getCompletedEntries() { return completedEntries; }
    }
} 