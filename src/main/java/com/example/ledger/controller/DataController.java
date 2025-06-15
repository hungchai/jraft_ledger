package com.example.ledger.controller;

import com.example.ledger.service.DataInitializationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/data")
@Tag(name = "Data Management API", description = "数据管理相关接口")
public class DataController {

    @Autowired
    private DataInitializationService dataInitializationService;

    @PostMapping("/reinitialize")
    @Operation(summary = "重新初始化RocksDB", description = "从MySQL重新加载数据到RocksDB")
    public ResponseEntity<Map<String, Object>> reinitializeData() {
        log.info("Manual re-initialization requested");
        
        Map<String, Object> response = new HashMap<>();
        
        try {
            dataInitializationService.forceReinitialize();
            
            response.put("success", true);
            response.put("message", "Data re-initialization completed successfully");
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to re-initialize data", e);
            
            response.put("success", false);
            response.put("message", "Data re-initialization failed: " + e.getMessage());
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/status")
    @Operation(summary = "获取数据状态", description = "获取RocksDB和MySQL的数据同步状态")
    public ResponseEntity<Map<String, Object>> getDataStatus() {
        log.info("Data status requested");
        Map<String, Object> response = new HashMap<>();
        response.put("rocksdbInitialized", "ALWAYS_REINITIALIZED_FROM_MYSQL_ON_STARTUP");
        response.put("timestamp", System.currentTimeMillis());
        response.put("status", "OK");
        return ResponseEntity.ok(response);
    }
} 