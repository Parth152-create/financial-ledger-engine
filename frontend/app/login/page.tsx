"use client"

import * as React from "react"
import Link from "next/link"
import { Eye, EyeOff, AlertCircle, Loader2 } from "lucide-react"
import { useAuth } from "@/hooks/auth/use-auth"
import { ROUTES } from "@/constants/routes"
import { ThemeToggle } from "@/components/layout/theme-toggle"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { ApiError } from "@/types/api"

export default function LoginPage() {
  const { loginWithGoogle, loginWithEmail } = useAuth()

  const [email, setEmail] = React.useState("")
  const [password, setPassword] = React.useState("")
  const [showPassword, setShowPassword] = React.useState(false)
  const [isLoading, setIsLoading] = React.useState(false)
  const [error, setError] = React.useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    setError(null)

    const trimmedEmail = email.trim()
    if (!trimmedEmail) {
      setError("Email address is required.")
      return
    }

    if (!password) {
      setError("Password is required.")
      return
    }

    setIsLoading(true)
    try {
      await loginWithEmail(trimmedEmail, password)
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message || "Invalid email or password.")
      } else if (err instanceof Error) {
        setError(err.message)
      } else {
        setError("An unexpected error occurred during sign in.")
      }
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex flex-col bg-background text-foreground select-none font-sans">
      <header className="h-13 border-b border-border px-4 sm:px-8 flex items-center justify-between bg-background">
        <Link href={ROUTES.HOME} className="flex items-center gap-2.5">
          <div className="size-6.5 rounded-sm bg-foreground text-background flex items-center justify-center font-bold text-xs tracking-tight">
            FL
          </div>
          <span className="text-[13.5px] font-semibold tracking-tight">
            Financial Ledger Engine
          </span>
        </Link>
        <ThemeToggle />
      </header>

      <main className="flex-1 flex items-center justify-center p-4">
        <div className="w-full max-w-sm rounded-sm border border-border bg-card p-6 text-card-foreground shadow-2xs space-y-5">
          <div className="space-y-1.5 text-center">
            <h1 className="text-[20px] font-semibold tracking-tight text-foreground font-sans">
              Sign in
            </h1>
            <p className="text-[13px] text-muted-foreground font-sans">
              Enter your credentials to access your financial workspace.
            </p>
          </div>

          {error && (
            <div
              role="alert"
              className="p-3 rounded-xs bg-destructive/10 border border-destructive/20 text-destructive text-[13px] flex items-start gap-2 leading-snug"
            >
              <AlertCircle className="size-4 shrink-0 mt-0.5" />
              <span>{error}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-3.5" noValidate>
            <div className="space-y-1.5">
              <label
                htmlFor="email"
                className="text-[13px] font-medium text-foreground block"
              >
                Email
              </label>
              <Input
                id="email"
                type="email"
                autoComplete="email"
                placeholder="name@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={isLoading}
                required
              />
            </div>

            <div className="space-y-1.5">
              <label
                htmlFor="password"
                className="text-[13px] font-medium text-foreground block"
              >
                Password
              </label>
              <div className="relative">
                <Input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  autoComplete="current-password"
                  placeholder="Enter your password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={isLoading}
                  className="pr-9"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((prev) => !prev)}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground focus:outline-none"
                  aria-label={showPassword ? "Hide password" : "Show password"}
                >
                  {showPassword ? (
                    <EyeOff className="size-4" />
                  ) : (
                    <Eye className="size-4" />
                  )}
                </button>
              </div>
            </div>

            <Button
              type="submit"
              disabled={isLoading}
              className="w-full h-8.5 justify-center text-[13.5px] font-medium mt-1"
            >
              {isLoading ? (
                <>
                  <Loader2 className="size-3.5 animate-spin mr-2" />
                  <span>Signing in...</span>
                </>
              ) : (
                <span>Sign in</span>
              )}
            </Button>
          </form>

          <div className="relative flex items-center justify-center my-3">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-border/80" />
            </div>
            <span className="relative bg-card px-2.5 text-[11.5px] font-medium uppercase tracking-wider text-muted-foreground">
              OR
            </span>
          </div>

          <Button
            type="button"
            variant="outline"
            onClick={loginWithGoogle}
            disabled={isLoading}
            className="w-full h-8.5 justify-center gap-2 text-[13px] font-medium"
          >
            <svg className="size-4" viewBox="0 0 24 24">
              <path
                fill="currentColor"
                d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"
              />
              <path
                fill="currentColor"
                d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
              />
              <path
                fill="currentColor"
                d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.06H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.94l2.85-2.22.81-.63z"
              />
              <path
                fill="currentColor"
                d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.06l3.66 2.84c.87-2.6 3.3-4.52 6.16-4.52z"
              />
            </svg>
            <span>Continue with Google</span>
          </Button>

          <div className="pt-2 border-t border-border/60 text-center">
            <span className="text-[12.5px] text-muted-foreground">
              Don&apos;t have an account?{" "}
              <Link
                href={ROUTES.SIGNUP}
                className="text-foreground font-medium underline underline-offset-4 hover:text-primary transition-colors"
              >
                Sign up
              </Link>
            </span>
          </div>
        </div>
      </main>

      <footer className="h-11 border-t border-border px-4 sm:px-8 flex items-center justify-between text-xs text-muted-foreground bg-muted/10">
        <span>Financial Ledger Engine</span>
        <span>Secure Session Management</span>
      </footer>
    </div>
  )
}
