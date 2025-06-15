package com.example.ledger.controller;

import com.example.ledger.service.AsyncMySQLBatchWriter;
import com.example.ledger.service.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin API", description = "Administrative endpoints for monitoring and management")
public class AdminController {

    @Autowired
    private AsyncMySQLBatchWriter asyncMySQLBatchWriter;

    @Autowired
    private IdempotencyService idempotencyService;

    @GetMapping("/metrics/mysql-writer")
    @Operation(summary = "Get MySQL writer metrics", description = "Retrieve performance metrics from the AsyncMySQLBatchWriter")
    public ResponseEntity<Map<String, Object>> getMySQLWriterMetrics() {
        log.info("Getting MySQL writer metrics");
        
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("metrics", asyncMySQLBatchWriter.getMetrics());
        metrics.put("timestamp", System.currentTimeMillis());
        
        return ResponseEntity.ok(metrics);
    }

    @GetMapping("/idempotency/stats")
    @Operation(summary = "获取幂等性缓存统计", description = "查看幂等性缓存的统计信息")
    public ResponseEntity<IdempotencyService.IdempotencyCacheStats> getIdempotencyStats() {
        IdempotencyService.IdempotencyCacheStats stats = idempotencyService.getCacheStats();
        return ResponseEntity.ok(stats);
    }
} 