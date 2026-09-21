# Financial Ledger Engine

A high-throughput financial ledger engine focused on double-entry accounting, transactional consistency, concurrency control, idempotency, reconciliation, and measurable performance.

## Architecture

- Spring Boot backend
- PostgreSQL as the financial source of truth
- Redis for auxiliary idempotency and fast-path operations
- Next.js frontend
- JMeter for load testing

## Core Principles

- Atomic financial transactions
- Double-entry ledger
- Immutable ledger entries
- Precise decimal monetary calculations
- Database-level concurrency control
- Idempotent transaction processing
- Balance-to-ledger reconciliation
- Measurable performance under load

## Project Structure

```text
financial-ledger-engine/
├── backend/
├── frontend/
├── load-testing/
├── docs/
└── database/
