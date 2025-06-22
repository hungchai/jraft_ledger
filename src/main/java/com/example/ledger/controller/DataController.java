package com.example.ledger.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

import com.example.ledger.service.RocksDBInitializationService;

@Slf4j
@RestController
@RequestMapping("/api/data")
@Tag(name = "Data Management API", description = "数据管理相关接口")
public class DataController {

    @Autowired
    private RocksDBInitializationService rocksDBInitializationService;

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

    @PostMapping("/initialize")
    @Operation(summary = "初始化RocksDB", description = "从MySQL加载数据到RocksDB")
    public ResponseEntity<Map<String, Object>> initializeRocksDB() {
        Map<String, Object> response = new HashMap<>();
        try {
            rocksDBInitializationService.initializeFromMySQL();
            response.put("success", true);
            response.put("message", "RocksDB initialization triggered.");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Initialization failed: " + e.getMessage());
        }
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }
} 