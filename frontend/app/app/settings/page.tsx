"use client"

import * as React from "react"
import Link from "next/link"
import { LogOut, Loader2, ShieldCheck, AlertCircle } from "lucide-react"
import { Section } from "@/components/ui/section"
import { DataRow } from "@/components/ui/data-row"
import { Button } from "@/components/ui/button"
import { StatusBadge } from "@/components/ui/status-badge"
import { ThemeSelector } from "@/components/settings/theme-selector"
import { PasswordForm } from "@/components/settings/password-form"
import { useAuth } from "@/hooks/auth/use-auth"
import { SITE_CONFIG } from "@/config/site"
import { ROUTES } from "@/constants/routes"
import { getSettingsErrorMessage } from "@/lib/validators/settings"

function SettingsSkeleton() {
  return (
    <div className="space-y-6 select-none font-sans animate-pulse">
      <div className="space-y-1.5 pb-3 border-b border-border/70">
        <div className="h-7 w-32 bg-muted rounded-xs" />
        <div className="h-4 w-72 bg-muted/60 rounded-xs" />
      </div>
      <div className="h-56 border border-border/70 rounded-sm bg-card" />
      <div className="h-64 border border-border/70 rounded-sm bg-card" />
      <div className="h-44 border border-border/70 rounded-sm bg-card" />
    </div>
  )
}

export default function SettingsPage() {
  const { user, isLoading, logout } = useAuth()
  const [isLoggingOut, setIsLoggingOut] = React.useState(false)
  const [logoutError, setLogoutError] = React.useState<string | null>(null)

  const handleLogout = async () => {
    setLogoutError(null)
    setIsLoggingOut(true)
    try {
      await logout()
    } catch (err) {
      setLogoutError(getSettingsErrorMessage(err))
      setIsLoggingOut(false)
    }
  }

  if (isLoading) {
    return <SettingsSkeleton />
  }

  return (
    <div className="space-y-6 select-none font-sans max-w-5xl">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/60">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-foreground font-sans">
            Settings
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            Account preferences, security credentials, and application appearance.
          </p>
        </div>
      </div>

      {/* 1. Account / Profile */}
      <Section
        title="Account Profile"
        description="Personal identity attributes and active session status."
      >
        <div className="space-y-1">
          <DataRow
            label="Full Name"
            value={user?.name || "—"}
            description="Display name associated with this ledger identity"
          />
          <DataRow
            label="Email Address"
            value={user?.email || "—"}
            monospace
            description="Verified primary email used for authentication"
          />
          <DataRow
            label="Session Status"
            value={<StatusBadge status="ACTIVE" />}
            description="Current authenticated session state"
          />
          <DataRow
            label="Authentication Model"
            value="Session-based (HTTP-Only Cookie)"
            description="Protected with CSRF verification and session fixation mitigation"
          />
        </div>

        <div className="mt-4 flex items-center gap-2 p-2.5 rounded-sm border border-border/60 bg-muted/20 text-xs text-muted-foreground">
          <ShieldCheck className="size-3.5 text-primary shrink-0" />
          <span>
            Profile details are managed by your identity provider and session service. Read-only presentation.
          </span>
        </div>
      </Section>

      {/* 2. Security & Credentials */}
      <Section
        title="Security & Credentials"
        description="Manage password credentials and active session termination."
      >
        <div className="space-y-6">
          {/* Password Management */}
          <div>
            <div className="mb-3">
              <h3 className="text-xs font-semibold text-foreground uppercase tracking-wider">
                Credential Management
              </h3>
              <p className="text-xs text-muted-foreground mt-0.5">
                Set or update the password credential associated with this account.
              </p>
            </div>
            <PasswordForm />
          </div>

          <div className="border-t border-border/60 pt-5">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
              <div>
                <h3 className="text-xs font-semibold text-foreground uppercase tracking-wider">
                  Active Session
                </h3>
                <p className="text-xs text-muted-foreground mt-0.5">
                  Terminate your current session across this browser. Secure session cookies and authentication cache will be cleared.
                </p>
              </div>

              <Button
                type="button"
                variant="destructive"
                size="sm"
                onClick={handleLogout}
                disabled={isLoggingOut}
                aria-label="Sign out of application"
                className="gap-1.5 text-[13px] self-start sm:self-auto shrink-0"
              >
                {isLoggingOut ? (
                  <>
                    <Loader2 className="size-3.5 animate-spin" />
                    <span>Signing Out...</span>
                  </>
                ) : (
                  <>
                    <LogOut className="size-3.5" />
                    <span>Sign Out</span>
                  </>
                )}
              </Button>
            </div>

            {logoutError && (
              <div
                role="alert"
                className="mt-3 flex items-center gap-2 p-2.5 rounded-sm border border-destructive/30 bg-destructive/10 text-xs text-destructive"
              >
                <AlertCircle className="size-3.5 shrink-0" />
                <span>{logoutError}</span>
              </div>
            )}
          </div>
        </div>
      </Section>

      {/* Financial Policies & Limits (Admin) */}
      <Section
        title="Financial Policies & Limits (Admin)"
        description="Platform transaction limits, daily cumulative quotas, and account balance caps."
      >
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 py-2">
          <div>
            <span className="font-medium text-foreground text-[13.5px] block">
              Policy Engine Configuration
            </span>
            <span className="text-[12.5px] text-muted-foreground">
              Configure global limits and account-specific policy overrides for transfers, deposits, and withdrawals.
            </span>
          </div>

          <Link href={ROUTES.SETTINGS_POLICIES}>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="gap-1.5 text-[13px] self-start sm:self-auto shrink-0"
            >
              <ShieldCheck className="size-3.5 text-primary" />
              <span>Manage Policies</span>
            </Button>
          </Link>
        </div>
      </Section>

      {/* 3. Appearance */}
      <Section
        title="Appearance"
        description="Visual presentation, theme preferences, and platform formatting conventions."
      >
        <div className="space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 py-2 border-b border-border/50">
            <div>
              <span className="font-medium text-foreground text-[13.5px] block">
                Interface Theme
              </span>
              <span className="text-[12.5px] text-muted-foreground">
                Choose between warm neutral light mode, graphite dark mode, or system default.
              </span>
            </div>
            <ThemeSelector />
          </div>

          <DataRow
            label="Platform Currency"
            value="Indian Rupee (INR / ₹)"
            description="Authoritative accounting denomination for financial transactions"
          />

          <DataRow
            label="Number Formatting"
            value="en-IN (Lakh / Crore notation)"
            description="Formatted with standard Indian numeric separators and two decimal precision"
          />
        </div>
      </Section>

      {/* 4. Application Information */}
      <Section
        title="Application Information"
        description="System specifications, accounting architecture, and security posture."
      >
        <div className="space-y-1">
          <DataRow
            label="Application Name"
            value={SITE_CONFIG.name}
            description="Core financial ledger and settlement engine"
          />
          <DataRow
            label="System Code"
            value={SITE_CONFIG.code}
            monospace
            description="Platform identifier code"
          />
          <DataRow
            label="Accounting Architecture"
            value="Double-Entry Ledger"
            description="Debits and credits are balanced per transaction"
          />
          <DataRow
            label="Settlement Model"
            value="Immediate Settlement"
            description="Transactions commit directly to the authoritative ledger"
          />
          <DataRow
            label="Security Posture"
            value="Session Protection & Encrypted Credentials"
            description="Defense-in-depth credential isolation and secure session management"
          />
        </div>
      </Section>
    </div>
  )
}
