import { TransferWorkflow } from "@/components/transfers/transfer-workflow"

export default function TransfersPage() {
  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Transfers & Transactions
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Execute atomic double-entry fund transfers between user accounts.
          </p>
        </div>

        <div className="flex items-center gap-1 border border-border/70 rounded-sm p-0.5 bg-muted/20">
          <button
            type="button"
            className="px-3 py-1 text-[13px] font-sans rounded-xs bg-foreground text-background font-medium"
          >
            Transfer
          </button>
          <button
            type="button"
            disabled
            title="Deposit feature milestone"
            className="px-3 py-1 text-[13px] font-sans rounded-xs text-muted-foreground/50 cursor-not-allowed"
          >
            Deposit
          </button>
          <button
            type="button"
            disabled
            title="Withdrawal feature milestone"
            className="px-3 py-1 text-[13px] font-sans rounded-xs text-muted-foreground/50 cursor-not-allowed"
          >
            Withdrawal
          </button>
        </div>
      </div>

      <TransferWorkflow />
    </div>
  )
}
