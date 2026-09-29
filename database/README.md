# Database Management

The PostgreSQL database is the authoritative financial source of truth for the Financial Ledger Engine.

## Migrations

All database schema migrations, integrity constraints, indexes, and immutability triggers are versioned and managed using **Flyway**.

Migration scripts are located under:

```text
backend/src/main/resources/db/migration/
```

- `V1__create_core_ledger_schema.sql` — Core users, accounts, transactions, and ledger entries tables.
- `V2__harden_account_model.sql` — Account types (`USER_CHECKING`, `SYSTEM_CLEARING`), lifecycle status, and public account numbers.
- `V3__enhance_transaction_auditability.sql` — Transaction types, initiating user auditability, and descriptions.
- `V4__finalize_database_integrity.sql` — PostgreSQL trigger preventing `UPDATE` and `DELETE` on `ledger_entries`, and composite index optimization.
- `V5__account_lifecycle_integrity.sql` — Terminal `CLOSED` account protections, zero-balance closure constraints, and lifecycle transition triggers.
- `V6__transaction_currency_integrity.sql` — Defense-in-depth currency formatting checks.
- `V7__create_user_credentials.sql` — Isolated credentials table for BCrypt password hashes.
- `V8__system_clearing_bootstrap_funding.sql` — `SYSTEM_TREASURY` account type and double-entry platform bootstrap funding.
- `V9__enforce_inr_only_platform_currency.sql` — Strict INR-only platform currency constraints (`chk_*_currency_inr`).
- `V10__create_audit_events_schema.sql` — Operational `audit_events` schema with partial unique indexes and immutability trigger.

## Documentation

For full details on the schema design, immutability, and constraints:
- [Database Integrity, Indexing & Ledger Immutability](../docs/DATABASE_INTEGRITY.md)
- [Account Model & Database Hardening](../docs/ACCOUNT_MODEL.md)
- [Transaction & Ledger Model Hardening](../docs/TRANSACTION_MODEL.md)
- [Audit Trail & Operational Audit Events](../docs/AUDIT_TRAIL_API.md)
