-- =============================================================================
-- V13__harden_policy_transaction_type_and_audit_dedup.sql
-- Financial Ledger Engine - Policy DB Constraint Hardening & Audit Deduplication
--
-- 1. Harden financial_policies transaction_type CHECK constraint:
--    - IF policy_type = 'ACCOUNT_BALANCE_LIMIT' => transaction_type MUST BE NULL
--    - IF policy_type != 'ACCOUNT_BALANCE_LIMIT' => transaction_type MUST BE NOT NULL
--      and strictly IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL')
--    - Fixes SQL three-valued logic where NULL IN (...) evaluated to UNKNOWN/TRUE.
--
-- 2. Create partial unique index on audit_events for policy rejection correlation:
--    - Scoped strictly to policy rejection event types
--    - Enforces database-authoritative deduplication on metadata ->> 'correlationId'
-- =============================================================================

-- 1. Harden transaction_type CHECK constraint on financial_policies
ALTER TABLE financial_policies DROP CONSTRAINT IF EXISTS chk_financial_policies_transaction_type;

ALTER TABLE financial_policies ADD CONSTRAINT chk_financial_policies_transaction_type CHECK (
    (policy_type = 'ACCOUNT_BALANCE_LIMIT' AND transaction_type IS NULL)
    OR
    (policy_type != 'ACCOUNT_BALANCE_LIMIT' AND transaction_type IS NOT NULL AND transaction_type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL'))
);

-- 2. Partial unique index enforcing at-most-one policy rejection audit event per correlation identity
CREATE UNIQUE INDEX IF NOT EXISTS uk_audit_events_policy_rejection_correlation
    ON audit_events((metadata ->> 'correlationId'))
    WHERE event_type IN ('TRANSFER_REJECTED_POLICY', 'DEPOSIT_REJECTED_POLICY', 'WITHDRAWAL_REJECTED_POLICY')
      AND metadata ->> 'correlationId' IS NOT NULL;
