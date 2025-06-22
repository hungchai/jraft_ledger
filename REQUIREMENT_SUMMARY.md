# JRaft Ledger Application: Requirements Draft

## 1. Technology Stack

- **Java 21**
- **Spring Boot 3** (REST API, configuration, dependency injection, actuator)
- **MySQL** (primary transactional database)
- **JRaft** (distributed consensus and replication)
- **RocksDB** (embedded key-value store for local state)
- **MyBatis Plus** (ORM for database access)
- **HikariCP** (JDBC connection pooling)
- **Swagger/OpenAPI** (API documentation via springdoc)
- **LMAX Disruptor** (high-performance event processing)
- **JUnit, H2** (testing)
- **Docker** (optional, for deployment/testing)
- **Lombok** (optional, for reducing boilerplate)

---

## 2. Distributed Ledger with JRaft

- The application provides a distributed ledger with strong consistency, using the Raft consensus algorithm (via Alibaba JRaft).
- JRaft manages cluster membership, leader election, and log replication.
- All ledger-modifying operations are coordinated through the Raft state machine to ensure consistency across nodes.
- RocksDB is used for local state persistence on each node.

---

## 3. RESTful API

The application exposes a RESTful API for ledger operations, administration, and status monitoring, documented via Swagger/OpenAPI.

**Endpoints include:**

- `POST /api/transfer` — Initiate a transfer between accounts.
- `GET /api/balance/{accountId}` — Query account balance.
- `POST /api/admin/` — Administrative operations (e.g., create account, reset ledger).
- `GET /api/data/` — Data initialization and inspection.
- `GET /api/raft/status` — Raft cluster status and diagnostics.

The API follows standard REST conventions, returns JSON, and uses appropriate HTTP status codes.

---

## 4. Database Resiliency and Startup Behavior

- The application fails fast if the database is unavailable at startup, and does not hang or enter a broken state.
- Uses HikariCP's `initialization-fail-timeout` to abort startup if a DB connection cannot be established within 10 seconds.
- On startup failure, the application exits cleanly and automatically, without requiring manual intervention.

---

## 5. Schema and Data Initialization

- The database schema is initialized automatically on startup using Spring Boot's standard mechanism (`schema.sql`).
- Business data initialization (e.g., creating default accounts) is decoupled from schema setup and only runs after the application is fully started and connected to the database.
- This is achieved by listening for the `ApplicationReadyEvent` in the `DataInitializationService`.

---

## 6. Maintainability and Standardization

- The solution relies on standard Spring Boot features and configuration for lifecycle, health checks, and shutdown logic.
- All custom health check and shutdown code has been removed in favor of Spring Boot's built-in mechanisms and configuration properties.

---

## 7. Idempotency and Transaction Logging

- The application ensures idempotency for ledger operations to prevent duplicate processing.
- All transactions are logged for audit and recovery purposes.

---

## 8. Performance and Batch Writing

- Supports asynchronous batch writing to MySQL for improved performance under high load, leveraging LMAX Disruptor for high-performance event processing.

---

## 9. Testing and Development

- Uses JUnit and H2 for automated testing.
- Docker can be used for local development and deployment environments. 