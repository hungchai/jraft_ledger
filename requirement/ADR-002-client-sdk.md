# ADR-002 Java Client SDK & Leader Discovery

**決策狀態**: Accepted  
**決策日期**: 2026-05-24  
**決策人**: Ledger Platform Team  
**影響範圍**: F-014 Client SDK, F-002 Posting, F-003 Manual Adjustment, F-004 Reversal, F-005 Balance Query, F-006 Journal Query, ADR-001 §2.3  
**依賴**: ADR-001 Raft + CQRS 架構

---

## 1. 背景與問題

ADR-001 確定所有帳務寫操作必須經由 Raft Leader 執行。這帶來一個對 Callers 極不友好的約束：**Client 必須自己知道當前 Leader 是誰**。

現有方案（純 HTTP/gRPC + 負載均衡器）的缺陷：

1. **透明負載均衡會把寫請求導到 Follower**，Follower 返回轉發錯誤或靜默失敗，Caller 必須自己實作重試。
2. **Leader 切換後 Caller 無感知**，in-flight 請求失敗，只能靠外部冪等重試，延遲飆高。
3. **每個 Caller 重複發明輪子**：連接池、快取、超時、重試、Leader 探測，導致各團隊實作不一致，運維困難。
4. **性能無法保證**：Generic HTTP client（如 Feign、RestTemplate）每個請求可能重建 TCP 連線，DNS 解析、TLS handshake 吃掉數百毫秒，直接違反 Posting P95 ≤ 3ms。

因此需要一個**官方 Java Client SDK**，內建 Leader 發現與容錯邏輯，讓 Caller 只需面對「Ledger Cluster」，不用面對「Leader Node」。

---

## 2. 決策

### 2.1 建立獨立 Maven 模組 `ledger-client-sdk`

- **不是** Generic HTTP client wrapper（Feign / RestTemplate）。
- **是** 專為 Ledger Raft 語義設計的有狀態客戶端，內建 Leader 發現、連線池、重試、快取。

理由：

| 考量 | Generic HTTP Client | Dedicated SDK |
|---|---|---|
| Leader 發現 | Caller 自己實作 | SDK 內建，統一行為 |
| 連線池預熱 | 無法保證 Leader 連線常駐 | 主動維持對 Leader 的 warm connection |
| 冪等重試語義 | Caller 自己判斷 | SDK 根據 requestId 自動重試寫入 |
| 性能預算 | 難以歸責 | SDK 內部監控，確保 overhead ≤ 0.5ms |
| 版本相容 | DTO 欄位變更容易導致序列化錯誤 | SDK 與 `ledger-core` 同版本發布，保證 DTO 一致 |

### 2.2 Leader Discovery 協議

```
SDK 啟動 / 快取失效:
  1. 對 endpoints 列表逐一發送 GET /raft/leader
  2. 第一個返回 200 + { leaderEndpoint, term } 的節點被採信
  3. 將 leaderEndpoint 寫入本地快取，啟動 TTL 計時器（預設 5s）

請求執行:
  1. 讀取快取中的 leaderEndpoint
  2. 透過連線池發送請求
  3. 若收到以下任一信號，視為「Leader 失效」:
       - TCP 連線失敗 / timeout
       - HTTP 503 Service Unavailable
       - HTTP 307 Temporary Redirect + Location header
       - Response body errorCode = NOT_LEADER
  4. 立即清除快取，觸發重新發現（回到步驟 1），最多 maxRetries 次
```

**關鍵設計**：

- **Stale hint 容忍**：快取 TTL 僅 5s，即使 Leader 在這 5s 內切換，最多只有 1 筆請求失敗，隨即刷新。
- **並發發現抑制**：多線程同時觸發發現時，只有一個線程執行探測，其餘等待結果（避免 Thundering Herd）。
- **任意節點皆可回答 /raft/leader**：Follower 也能返回當前 Leader 資訊，因此探測不需要命中 Leader。

### 2.3 重試語義

| 操作類型 | 範例 | 重試策略 |
|---|---|---|
| 冪等寫入 | post(), reverse(), approveAdjustment() | 自動重試（requestId 已存在於請求中），最多 3 次，指數退避 10ms → 20ms → 40ms |
| 非冪等讀取 | queryBalance(), queryJournal() | Failover 到任意節點，不帶 retry body（GET 冪等），最多 2 次 |
| 非冪等寫入 | createDraft()（Draft 本身非冪等，但 requestId 可冪等） | 僅在收到明確 NOT_LEADER 時重試 1 次 |

**禁止**：SDK 不會對 HTTP 400/422（業務錯誤）進行重試，只對網路層或 Leader 層錯誤重試。

### 2.4 連線池與性能

- **HTTP Client**：Apache HttpClient 5（非阻塞 IO，連線池成熟）。
- **連線池配置**：
  - maxTotal = 200
  - maxPerRoute = 50（對 Leader endpoint 的連線數）
  - keep-alive = 30s（大於 TTL，確保連線常駐）
- **TLS**：若啟用 mTLS，憑證與連線池在 SDK 初始化時預載，請求路徑不再碰磁碟。
- **Latency budget**：

```
Total Posting P95 ≤ 3ms
  ├─ Network RTT (same AZ)     ~ 0.2ms
  ├─ Ledger RESTful Controller  ~ 0.3ms
  ├─ Account Queue + Raft       ~ 1.5ms
  ├─ State Machine apply        ~ 0.8ms
  └─ SDK overhead (target)      ≤ 0.5ms  ← 本 ADR 負責
       ├─ 快取讀取 (ConcurrentHashMap)  < 0.01ms
       ├─ 連線池取得 (warm)            < 0.05ms
       ├─ 序列化 (Jackson afterburner)  < 0.1ms
       └─ 重試懲罰 (極少觸發)          < 0.3ms
```

若 Leader 切換導致重試，單次額外延遲約 10–50ms（發現新 Leader + 重發），此為異常路徑，不計入常態 P95。

### 2.5 模組邊界

```
ledger-client-sdk
  └─ depends on: ledger-core (DTOs only)
  └─ NOT depends on: ledger-dao, ledger-restful, jraft-core, sofa-jraft
```

- `ledger-core` 提供 `PostingRequest`, `BalanceQueryRequest`, `CommandResult` 等 DTO。
- SDK 只認識 DTO，不認識 Raft Command、RocksDB、State Machine。
- 避免版本鎖定：當 `ledger-core` DTO 欄位變更時，SDK 跟隨發布新版本，Caller 只需升級 SDK 版本。

### 2.6 失敗模式與熔斷

| 場景 | 行為 |
|---|---|
| 所有節點皆無法連線 | 拋出 `LedgerClientException(errorCode=CLUSTER_UNAVAILABLE)`，帶有最後一個底層例外 |
| 所有節點皆返回「無 Leader」（選舉中） | 拋出 `LedgerClientException(errorCode=NO_LEADER_AVAILABLE)`，建議 Caller 延遲重試 |
| 連續 10 次 Leader 發現失敗 | 可選熔斷器（Resilience4j CircuitBreaker）開啟，後續請求快速失敗，週期 30s 後半開 |
| 單一請求超過 maxRetries | 拋出 `LedgerClientException(errorCode=MAX_RETRIES_EXCEEDED)`，附带最後一次錯誤詳情 |

---

## 3. 後果

### 3.1 正面

- **Caller 零負擔**：無需理解 Raft Leader 概念，把 Ledger 當成單一服務調用。
- **性能可預測**：連線池預熱 + Leader 快取，常態 overhead < 0.5ms。
- **統一容錯**：所有產品團隊共用同一套重試、發現、熔斷邏輯，運維一致性高。
- **測試友善**：SDK 接口易於 Mock，單元測試不需要啟動 Raft 集群。

### 3.2 負面

- **額外模組維護**：SDK 發布週期必須與 `ledger-core` DTO 保持同步。
- **Java 綁定**：非 Java Callers（Go, Python）無法直接使用，需另行開發對應 SDK。
- **Leader 快取延遲**：TTL 5s 內的 Leader 切換會導致 1 次失敗請求（隨即刷新，可接受）。

### 3.3 中性

- **健康檢查**：SDK 內部可暴露 `LeaderDiscoveryMetrics`（Micrometer），供 Prometheus 抓取，運維可觀察 Leader 切換頻率與發現延遲。

---

## 4. 替代方案考慮

| 方案 | 放棄原因 |
|---|---|
| Feign + Ribbon/Eureka | Eureka 註冊的是 HTTP endpoint，無法表達「只有 Leader 能寫」的語義；Ribbon 重試不區分冪等寫入與非冪等讀取。 |
| gRPC NameResolver + custom LB | gRPC 負載均衡器無法主動探測 HTTP /raft/leader endpoint，且目前 Ledger RESTful 層為 HTTP/JSON，引入 gRPC client 增加複雜度。 |
| Sidecar Proxy（Envoy） | 需要額外部署與維護 Proxy，且 Ledger 的 Leader 語義非標準 HTTP health check 能表達，仍需自定義控制面。 |
| Caller 自研（每個團隊自己寫） | 重複發明輪子，行為不一致，難以統一監控與升級。 |

---

## 5. 合規與驗證

| 檢查項 | 驗證方式 |
|---|---|
| SDK overhead ≤ 0.5ms | TC-NFR-01 壓測中單獨測量 SDK 層延遲（Mock Server） |
| Leader 切換後自動恢復 | TC-F014-03 單元測試：模擬 503 → 觸發發現 → 成功 |
| 冪等寫入重試不雙重入帳 | TC-F014-04 整合測試：Leader 宕機後新 Leader 接受同一 requestId |
| Module 邊界無 Raft 依賴 | `mvn dependency:analyze` 檢查 `ledger-client-sdk` 不依賴 `jraft-core` |
| ADR-001 架構圖更新 | ADR-001 §2.3 ASCII 圖已納入 Client SDK 層 |

---

## 6. 序列圖：Leader Discovery & Retry

```
Caller          SDK                 Cache           Node-A          Node-B(L)      Node-C
  |              |                    |               |               |              |
  | post(req)    |                    |               |               |              |
  |------------->| read leaderHint    |               |               |              |
  |              |------------------->| MISS          |               |              |
  |              | start discovery    |               |               |              |
  |              |----------------------------------->| GET /raft/leader            |
  |              |                    |               | 200 {leader:B}                |
  |              |<-----------------------------------|               |              |
  |              | write leaderHint   |               |               |              |
  |              |------------------->| B             |               |              |
  |              | POST /ledger/postings (to B)                      |              |
  |              |-------------------------------------------------->|              |
  |              |                    |               |               | apply Raft   |
  |              |<--------------------------------------------------| 200 OK       |
  | 200 OK       |                    |               |               |              |
  |<-------------|                    |               |               |              |
  |              |                    |               |               |              |
  ~ ~ ~ Leader B steps down ~ ~ ~                                    |              |
  |              |                    |               |               |              |
  | post(req2)   |                    |               |               |              |
  |------------->| read leaderHint    |               |               |              |
  |              |------------------->| B (stale)     |               |              |
  |              | POST /ledger/postings (to B)                      |              |
  |              |-------------------------------------------------->| 503 NOT_LEADER
  |              |<--------------------------------------------------|              |
  |              | invalidate hint    |               |               |              |
  |              |------------------->| null          |               |              |
  |              | retry + re-discover                |               |              |
  |              |----------------------------------->| GET /raft/leader            |
  |              |                    |               | 200 {leader:C}               |
  |              |<-----------------------------------|               |              |
  |              | POST /ledger/postings (to C)                                     |
  |              |----------------------------------------------------------------->|
  |              |                    |               |               |              | apply
  |              |<-----------------------------------------------------------------| 200 OK
  | 200 OK       |                    |               |               |              |
  |<-------------|                    |               |               |              |
```

---

## 7. 修訂記錄

| 版本 | 日期 | 修訂內容 | 修訂人 |
|---|---|---|---|
| v0.1 | 2026-05-24 | 初稿：Leader Discovery、Retry、Module Boundary、Sequence Diagram | Ledger Platform Team |
