import {
  LayoutDashboard,
  Landmark,
  ArrowLeftRight,
  CalendarClock,
  BookOpenText,
  BarChart3,
  Scale,
  ShieldCheck,
  Settings,
  type LucideIcon,
} from "lucide-react"
import { ROUTES } from "@/constants/routes"

export interface NavItem {
  title: string
  href: string
  icon: LucideIcon
  exact?: boolean
  section?: "Operations" | "Audit & Reporting" | "System"
}

export const SITE_CONFIG = {
  name: "Financial Ledger Engine",
  code: "FL",
  description: "A reliable double-entry ledger platform for processing, tracking, and reconciling financial transactions.",
  navigation: [
    {
      title: "Overview",
      href: ROUTES.DASHBOARD,
      icon: LayoutDashboard,
      exact: true,
      section: "Operations",
    },
    {
      title: "Accounts",
      href: ROUTES.ACCOUNTS,
      icon: Landmark,
      section: "Operations",
    },
    {
      title: "Transfers",
      href: ROUTES.TRANSFERS,
      icon: ArrowLeftRight,
      section: "Operations",
    },
    {
      title: "Recurring Transfers",
      href: ROUTES.RECURRING_TRANSFERS,
      icon: CalendarClock,
      section: "Operations",
    },
    {
      title: "Ledger",
      href: ROUTES.LEDGER,
      icon: BookOpenText,
      section: "Audit & Reporting",
    },
    {
      title: "Analytics",
      href: ROUTES.ANALYTICS,
      icon: BarChart3,
      section: "Audit & Reporting",
    },
    {
      title: "Reconciliation",
      href: ROUTES.RECONCILIATION,
      icon: Scale,
      section: "Audit & Reporting",
    },
    {
      title: "Audit Trail",
      href: ROUTES.AUDIT,
      icon: ShieldCheck,
      section: "Audit & Reporting",
    },
    {
      title: "Settings",
      href: ROUTES.SETTINGS,
      icon: Settings,
      section: "System",
    },
  ] satisfies NavItem[],
}
