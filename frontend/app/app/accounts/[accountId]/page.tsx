import Link from "next/link"
import { ArrowLeft, ArrowLeftRight, Download } from "lucide-react"
import { AccountHeader } from "@/components/accounts/account-header"
import { AccountMeta } from "@/components/accounts/account-meta"
import { Section } from "@/components/ui/section"
import { Button } from "@/components/ui/button"
import { ROUTES } from "@/constants/routes"

export default async function AccountDetailPage({
  params,
}: {
  params: Promise<{ accountId: string }>
}) {
  const { accountId } = await params

  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div className="flex items-center gap-2">
          <Link href={ROUTES.ACCOUNTS}>
            <Button variant="ghost" size="xs" className="gap-1 text-xs">
              <ArrowLeft className="size-3.5" />
              <span>Accounts</span>
            </Button>
          </Link>
          <span className="text-muted-foreground/40">/</span>
          <span className="text-xs font-mono text-muted-foreground truncate max-w-xs">{accountId}</span>
        </div>

        <div className="flex items-center gap-2">
          <Link href={ROUTES.TRANSFERS}>
            <Button size="sm" className="gap-1.5">
              <ArrowLeftRight className="size-3.5" />
              <span>Transfer Funds</span>
            </Button>
          </Link>
        </div>
      </div>

      <AccountHeader
        accountId={accountId}
        accountNumber={`ACCT-${accountId.slice(0, 8).toUpperCase()}`}
        accountType="Checking"
        currency="INR"
        status="ACTIVE"
        balance="—"
        actions={
          <Button variant="outline" size="sm" className="gap-1.5">
            <Download className="size-3.5" />
            <span>Download Statement</span>
          </Button>
        }
      />

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div className="md:col-span-1">
          <AccountMeta
            accountId={accountId}
            currency="INR"
            accountType="USER_CHECKING"
            status="ACTIVE"
            createdAt="—"
            reconciliationStatus="CONSISTENT"
          />
        </div>

        <div className="md:col-span-2">
          <Section
            title="Account Transaction History"
            description="All debit and credit movements affecting this account."
          >
            <div className="border border-border/70 rounded-sm overflow-hidden">
              <div className="grid grid-cols-12 gap-3 px-4 py-2 bg-muted/30 text-xs text-muted-foreground font-medium border-b border-border/70">
                <span className="col-span-3">Date & Time</span>
                <span className="col-span-3">Type</span>
                <span className="col-span-2 text-center">Flow</span>
                <span className="col-span-2 text-right">Amount</span>
                <span className="col-span-2 text-right">Status</span>
              </div>

              <div className="p-10 text-center space-y-2 bg-card">
                <p className="text-xs font-medium text-foreground">
                  No transactions recorded
                </p>
                <p className="text-xs text-muted-foreground max-w-xs mx-auto">
                  Transactions affecting this checking instrument will appear here once executed.
                </p>
              </div>
            </div>
          </Section>
        </div>
      </div>
    </div>
  )
}
