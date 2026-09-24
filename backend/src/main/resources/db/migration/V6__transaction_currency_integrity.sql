-- =============================================================================
-- V6__transaction_currency_integrity.sql
-- Financial Ledger Engine - Transaction Currency Defense-in-Depth
-- Enforces ISO 4217 3-letter uppercase alphabetic currency code on transactions
-- =============================================================================

ALTER TABLE transactions
    ADD CONSTRAINT chk_transactions_currency_format
    CHECK (currency ~ '^[A-Z]{3}$');
