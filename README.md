# JRaft 分散式帳本系統

基於 JRaft 共識算法的分散式雙記帳法 (Double-Entry) 帳本系統，使用 Java 21 和 SpringBoot 3.2。

## 特色功能

- **強一致性**: 使用 JRaft 確保所有節點的資料一致性
- **雙記帳法**: 每筆交易都遵循複式簿記原則
- **原子性操作**: 支援多筆轉帳的原子性批量操作
- **容錯性**: 分散式架構提供高可用性

## 快速開始

### 1. 編譯專案

```bash
mvn clean package
```

### 2. 啟動應用程式

```bash
java -jar target/jraft-ledger-system-1.0.0.jar
```

### 3. 創建測試帳戶

```bash
# 創建帳戶 A
curl -X POST http://localhost:8080/api/ledger/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "accountNumber": "ACC001",
    "accountName": "張三",
    "accountType": "ASSET",
    "initialBalance": 1000.00
  }'

# 創建帳戶 B  
curl -X POST http://localhost:8080/api/ledger/accounts \
  -H "Content-Type: application/json" \
  -d '{
    "accountNumber": "ACC002", 
    "accountName": "李四",
    "accountType": "ASSET",
    "initialBalance": 500.00
  }'
```

### 4. 執行轉帳

```bash
# 單筆轉帳
curl -X POST http://localhost:8080/api/ledger/transfer \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountNumber": "ACC001",
    "toAccountNumber": "ACC002", 
    "amount": 100.00,
    "description": "轉帳測試"
  }'

# 批量轉帳
curl -X POST http://localhost:8080/api/ledger/batch-transfer \
  -H "Content-Type: application/json" \
  -d '[
    {
      "fromAccountNumber": "ACC001",
      "toAccountNumber": "ACC002",
      "amount": 50.00,
      "description": "批量轉帳 1"
    },
    {
      "fromAccountNumber": "ACC002", 
      "toAccountNumber": "ACC001",
      "amount": 25.00,
      "description": "批量轉帳 2"
    }
  ]'
```

### 5. 查詢帳戶餘額

```bash
# 查詢所有帳戶
curl http://localhost:8080/api/ledger/accounts

# 查詢特定帳戶
curl http://localhost:8080/api/ledger/accounts/ACC001

# 查詢節點狀態
curl http://localhost:8080/api/ledger/status
```

## API 端點

| 方法 | 端點 | 描述 |
|------|------|------|
| POST | `/api/ledger/accounts` | 創建新帳戶 |
| GET | `/api/ledger/accounts` | 獲取所有帳戶 |
| GET | `/api/ledger/accounts/{accountNumber}` | 獲取特定帳戶 |
| POST | `/api/ledger/transfer` | 執行單筆轉帳 |
| POST | `/api/ledger/batch-transfer` | 執行批量轉帳 |
| GET | `/api/ledger/status` | 獲取節點狀態 |

## 帳戶類型

- `ASSET`: 資產帳戶
- `LIABILITY`: 負債帳戶  
- `EQUITY`: 權益帳戶
- `REVENUE`: 收入帳戶
- `EXPENSE`: 支出帳戶

## JRaft 配置

在 `application.yml` 中可以配置：

- `raft.node.id`: 節點 ID
- `raft.node.port`: 節點端口
- `raft.group.id`: Raft 組 ID
- `raft.data.path`: 資料存儲路徑
- `raft.cluster.config`: 集群配置

## 分散式部署

要部署多節點集群：

1. 修改每個節點的 `raft.node.id` 和 `raft.node.port`
2. 更新 `raft.cluster.config` 包含所有節點
3. 確保所有節點可以互相通信

例如：
```yaml
raft:
  cluster:
    config: 127.0.0.1:8081,127.0.0.1:8082,127.0.0.1:8083
```

## 資料庫

預設使用 H2 記憶體資料庫，可以透過 http://localhost:8080/h2-console 訪問。

連接參數：
- JDBC URL: `jdbc:h2:mem:ledger`
- 用戶名: `sa`
- 密碼: (空白) 