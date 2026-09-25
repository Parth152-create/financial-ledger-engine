import { Section } from "@/components/ui/section"
import { DataRow } from "@/components/ui/data-row"
import { ThemeToggle } from "@/components/layout/theme-toggle"

export default function SettingsPage() {
  return (
    <div className="space-y-6 select-none font-sans">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-3 border-b border-border/70">
        <div>
          <h1 className="text-[26px] font-semibold tracking-tight text-foreground font-sans leading-tight">
            Settings
          </h1>
          <p className="text-[14px] text-muted-foreground font-sans mt-0.5">
            System configuration, backend connection parameters, and appearance settings.
          </p>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <Section title="Backend Connection">
          <div className="space-y-1">
            <DataRow label="API Server URL" value="http://localhost:8085" monospace />
            <DataRow label="Frontend Port" value="3001" monospace />
            <DataRow label="Database Engine" value="PostgreSQL 16" />
            <DataRow label="Idempotency Store" value="Redis" />
            <DataRow label="Flyway Migrations" value="V1..V4 Applied" />
          </div>
        </Section>

        <Section title="Security & Authentication">
          <div className="space-y-1">
            <DataRow label="Auth Method" value="Google OAuth2 / OIDC" />
            <DataRow label="Session Handling" value="HTTP-Only Cookie Sessions" />
            <DataRow label="CORS Integration" value="Authorized for 3000, 3001" />
            <DataRow label="Browser Credentials" value="include (Session-based)" />
            <DataRow label="Role Authorization" value="ROLE_USER / ROLE_ADMIN" />
          </div>
        </Section>
      </div>

      <Section title="Appearance & Preferences">
        <div className="flex items-center justify-between py-2.5 border-b border-border/40 text-[13.5px]">
          <div>
            <span className="font-medium text-foreground block">Theme</span>
            <span className="text-[12.5px] text-muted-foreground">
              Switch between warm neutral light mode and deep graphite dark mode.
            </span>
          </div>
          <ThemeToggle />
        </div>

        <div className="flex items-center justify-between py-2.5 text-[13.5px]">
          <div>
            <span className="font-medium text-foreground block">Number Formatting</span>
            <span className="text-[12.5px] text-muted-foreground">
              Tabular monospace figures applied to balances and transaction IDs.
            </span>
          </div>
          <span className="text-[13px] text-muted-foreground font-mono">
            Indian Rupee (INR)
          </span>
        </div>
      </Section>
    </div>
  )
}
