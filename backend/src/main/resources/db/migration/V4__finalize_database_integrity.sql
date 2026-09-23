-- =============================================================================
-- V4__finalize_database_integrity.sql
-- Financial Ledger Engine - Database Integrity, Indexing & Ledger Immutability
-- 1. Ledger immutability: PostgreSQL trigger preventing UPDATE and DELETE on ledger_entries
-- 2. Index audit and query-pattern composite index optimization
-- =============================================================================

-- 1. Ledger Immutability Trigger
CREATE OR REPLACE FUNCTION prevent_ledger_entry_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are immutable: % operations are not allowed on ledger_entries', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_immutable
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW
EXECUTE FUNCTION prevent_ledger_entry_modification();

-- 2. Index Audit & Optimization

-- Replace redundant single-column index idx_ledger_entries_account_id with composite index
-- (account_id, created_at). The leading column satisfies foreign key validation and single-account
-- lookups, while also accelerating timeline and statement queries without secondary sorting.
DROP INDEX IF EXISTS idx_ledger_entries_account_id;
CREATE INDEX idx_ledger_entries_account_id_created_at ON ledger_entries(account_id, created_at);

-- Replace redundant single-column index idx_transactions_source_account_id with composite index
-- (source_account_id, created_at). The leading column satisfies foreign key validation,
-- while also supporting chronological transaction history queries for debited accounts.
DROP INDEX IF EXISTS idx_transactions_source_account_id;
CREATE INDEX idx_transactions_source_account_id_created_at ON transactions(source_account_id, created_at);

-- Replace redundant single-column index idx_transactions_destination_account_id with composite index
-- (destination_account_id, created_at). The leading column satisfies foreign key validation,
-- while also supporting chronological transaction history queries for credited accounts.
DROP INDEX IF EXISTS idx_transactions_destination_account_id;
CREATE INDEX idx_transactions_destination_account_id_created_at ON transactions(destination_account_id, created_at);
