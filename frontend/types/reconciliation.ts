export type ReconciliationStatus = "CONSISTENT" | "DISCREPANCY"

export interface ReconciliationResult {
  accountId: string
  snapshotBalance: number
  ledgerBalance: number
  difference: number
  status: ReconciliationStatus
  totalCredits: number
  totalDebits: number
  reconciledAt: string
}

export interface OverallReconciliation {
  totalAccountsChecked: number
  consistentAccounts: number
  discrepancyCount: number
  reconciliationResults: ReconciliationResult[]
  reconciledAt: string
}
