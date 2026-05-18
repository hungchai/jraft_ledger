I have attached the full technical requirements for a next-generation
internal ledger platform for an iBank.

Key architecture decisions (ADR-001):
- Raft consensus (SOFAJRaft) + CQRS
- Account-Level In-Memory Queue (per account serialization)
- RocksDB for Raft log + State Machine persistence
- MySQL as read-only View Layer (synced by Raft Learner)
- Java 21 + Spring Boot 3 + Virtual Threads
- No ORM (MyBatis only), no Redis in write path

Please start by generating:
1. Maven project structure
2. Core domain models (Journal, JournalLine, Account, BalanceEntry)
3. RocksDB Column Family setup
4. SOFAJRaft StateMachine skeleton

Do not deviate from ADR-001 architecture constraints.

Please implement the code to make all test cases in TDD-TEST-CASES.md pass.
Start with Phase 1 (F001 + F010 + F008 basic balance operations).
Write the test class first, then the implementation.
Use JUnit 5 + AssertJ + Mockito.

requirement under ./requirement folder
local environment has sdkman
we need a complete docker compose to run the integration test , e2e test . 

the folder structure 
1. one module for dao mybatic
2. one module for service
3. one module for restful 
4. one module for OpenFeign 

use maven
