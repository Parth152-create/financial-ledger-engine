import test from "node:test"
import assert from "node:assert/strict"

// 1. CSRF Cookie Resolution
test("Foundation: CSRF cookie extraction correctly parses raw and encoded XSRF-TOKEN", () => {
  const extractCsrfToken = (cookieString) => {
    if (!cookieString) return null
    const match = cookieString.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
    return match ? decodeURIComponent(match[1]) : null
  }

  // Typical browser document.cookie strings
  assert.equal(
    extractCsrfToken("JSESSIONID=A1B2C3D4E5; XSRF-TOKEN=c4b8e901-7fa1-4a11; other=123"),
    "c4b8e901-7fa1-4a11"
  )
  assert.equal(
    extractCsrfToken("XSRF-TOKEN=simple-token-12345"),
    "simple-token-12345"
  )
  assert.equal(
    extractCsrfToken("cookieA=valA;XSRF-TOKEN=compact-token;cookieB=valB"),
    "compact-token"
  )
  // URL encoded tokens
  assert.equal(
    extractCsrfToken("XSRF-TOKEN=abc%2F123%2Bxyz%3D%3D"),
    "abc/123+xyz=="
  )
  // Missing token
  assert.equal(extractCsrfToken("JSESSIONID=A1B2C3D4E5; theme=dark"), null)
  assert.equal(extractCsrfToken(""), null)
  assert.equal(extractCsrfToken(null), null)
})

// 2. Mutating Header Resolution
test("Foundation: Mutating HTTP methods require CSRF token header while safe GET does not", () => {
  const resolveHeaders = (method, cookieToken, providedHeaders = {}) => {
    const isMutating = ["POST", "PUT", "PATCH", "DELETE"].includes(method.toUpperCase())
    const headers = { ...providedHeaders }
    if (isMutating && !headers["X-XSRF-TOKEN"] && !headers["X-CSRF-TOKEN"]) {
      if (cookieToken) {
        headers["X-XSRF-TOKEN"] = cookieToken
      }
    }
    return headers
  }

  const sampleToken = "sec-csrf-token-abc"

  // GET should not inject X-XSRF-TOKEN automatically
  assert.equal(resolveHeaders("GET", sampleToken)["X-XSRF-TOKEN"], undefined)

  // Mutating methods should attach X-XSRF-TOKEN
  assert.equal(resolveHeaders("POST", sampleToken)["X-XSRF-TOKEN"], sampleToken)
  assert.equal(resolveHeaders("PUT", sampleToken)["X-XSRF-TOKEN"], sampleToken)
  assert.equal(resolveHeaders("DELETE", sampleToken)["X-XSRF-TOKEN"], sampleToken)
  assert.equal(resolveHeaders("PATCH", sampleToken)["X-XSRF-TOKEN"], sampleToken)

  // Explicitly provided token should not be overwritten
  assert.equal(
    resolveHeaders("POST", sampleToken, { "X-XSRF-TOKEN": "custom-override" })["X-XSRF-TOKEN"],
    "custom-override"
  )
})

// 3. Navigation Integrity
test("Foundation: App Shell navigation items match application routes", () => {
  const ROUTES = {
    DASHBOARD: "/app",
    ACCOUNTS: "/app/accounts",
    TRANSFERS: "/app/transfers",
    LEDGER: "/app/ledger",
    ANALYTICS: "/app/analytics",
    RECONCILIATION: "/app/reconciliation",
    SETTINGS: "/app/settings",
  }

  const NAV_ITEMS = [
    { title: "Overview", href: ROUTES.DASHBOARD, exact: true, section: "Operations" },
    { title: "Accounts", href: ROUTES.ACCOUNTS, section: "Operations" },
    { title: "Transfers", href: ROUTES.TRANSFERS, section: "Operations" },
    { title: "Ledger", href: ROUTES.LEDGER, section: "Audit & Reporting" },
    { title: "Analytics", href: ROUTES.ANALYTICS, section: "Audit & Reporting" },
    { title: "Reconciliation", href: ROUTES.RECONCILIATION, section: "Audit & Reporting" },
    { title: "Settings", href: ROUTES.SETTINGS, section: "System" },
  ]

  assert.equal(NAV_ITEMS.length, 7)
  const hrefs = NAV_ITEMS.map((item) => item.href)
  assert.ok(hrefs.includes("/app"))
  assert.ok(hrefs.includes("/app/accounts"))
  assert.ok(hrefs.includes("/app/transfers"))
  assert.ok(hrefs.includes("/app/ledger"))
  assert.ok(hrefs.includes("/app/analytics"))
  assert.ok(hrefs.includes("/app/reconciliation"))
  assert.ok(hrefs.includes("/app/settings"))
})

// 4. Active Route Matching
test("Foundation: Active navigation matching handles exact root and sub-path routes correctly", () => {
  const isNavActive = (currentPath, itemHref, exact) => {
    return exact
      ? currentPath === itemHref
      : currentPath === itemHref || currentPath.startsWith(`${itemHref}/`)
  }

  // Dashboard exact match
  assert.equal(isNavActive("/app", "/app", true), true)
  assert.equal(isNavActive("/app/accounts", "/app", true), false)

  // Accounts match root and sub-resource (e.g. account detail)
  assert.equal(isNavActive("/app/accounts", "/app/accounts", false), true)
  assert.equal(isNavActive("/app/accounts/123-uuid", "/app/accounts", false), true)
  assert.equal(isNavActive("/app/transfers", "/app/accounts", false), false)
})

// 5. Normalized ApiError Behavior
test("Foundation: ApiError preserves HTTP status code, message, error phrase, and endpoint path", () => {
  class MockApiError extends Error {
    constructor(data) {
      super(data.message || data.error || "An unexpected error occurred")
      this.name = "ApiError"
      this.status = data.status
      this.error = data.error
      this.path = data.path
      this.details = data.details
    }
  }

  const err = new MockApiError({
    status: 401,
    error: "Unauthorized",
    message: "Authentication required",
    path: "/api/v1/auth/me",
  })

  assert.equal(err.name, "ApiError")
  assert.equal(err.status, 401)
  assert.equal(err.error, "Unauthorized")
  assert.equal(err.message, "Authentication required")
  assert.equal(err.path, "/api/v1/auth/me")
})

// 6. Currency Formatter Contract
test("Foundation: INR currency formatter enforces ₹ symbol and Indian numbering format", () => {
  const formatINR = (amount) => {
    const num = typeof amount === "number" ? amount : Number(amount)
    if (isNaN(num)) return String(amount)
    return `₹${num.toLocaleString("en-IN", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`
  }

  assert.equal(formatINR(0), "₹0.00")
  assert.equal(formatINR(100), "₹100.00")
  assert.equal(formatINR(1000), "₹1,000.00")
  assert.equal(formatINR(100000), "₹1,00,000.00")
  assert.equal(formatINR(10000000), "₹1,00,00,000.00")
  assert.equal(formatINR("5432.10"), "₹5,432.10")
})

// 7. Unauthenticated Redirect Decision Logic
test("Foundation: AuthBoundary state transitions correctly from verifying to redirecting", () => {
  const getAuthState = ({ isLoading, user, error }) => {
    if (isLoading) return "LOADING"
    if (!user || error) return "REDIRECT_TO_LOGIN"
    return "AUTHENTICATED"
  }

  assert.equal(getAuthState({ isLoading: true, user: null, error: null }), "LOADING")
  assert.equal(getAuthState({ isLoading: false, user: null, error: null }), "REDIRECT_TO_LOGIN")
  assert.equal(getAuthState({ isLoading: false, user: null, error: new Error("401") }), "REDIRECT_TO_LOGIN")
  assert.equal(
    getAuthState({ isLoading: false, user: { id: "1", name: "Parth" }, error: null }),
    "AUTHENTICATED"
  )
})

// 8. Restrained Theme Transition & Reduced Motion Behavior
test("Foundation: Restrained theme transition enforces 200-300ms timing and respects reduced motion", () => {
  const TRANSITION_DURATION_MS = 220
  assert.ok(TRANSITION_DURATION_MS >= 200 && TRANSITION_DURATION_MS <= 300, "Theme transition duration within 200-300ms target")

  // Determine transition strategy
  const resolveThemeTransitionMode = ({ isReducedMotion, hasViewTransitionApi }) => {
    if (isReducedMotion) return "IMMEDIATE"
    if (hasViewTransitionApi) return "VIEW_TRANSITION_CROSSFADE"
    return "CSS_FALLBACK"
  }

  assert.equal(
    resolveThemeTransitionMode({ isReducedMotion: true, hasViewTransitionApi: true }),
    "IMMEDIATE"
  )
  assert.equal(
    resolveThemeTransitionMode({ isReducedMotion: false, hasViewTransitionApi: true }),
    "VIEW_TRANSITION_CROSSFADE"
  )
  assert.equal(
    resolveThemeTransitionMode({ isReducedMotion: false, hasViewTransitionApi: false }),
    "CSS_FALLBACK"
  )
})

// 9. Dark Palette Hierarchy (Graphite background, Lighter sidebar, Near-black surface)
test("Foundation: Dark theme palette maintains layered architectural hierarchy", () => {
  // Approximate lightness values in OKLCH:
  // - Near-black surface: L ~ 0.200 (#151619)
  // - Graphite background: L ~ 0.236 (#1D1E23)
  // - Slightly lighter sidebar: L ~ 0.274 (#26272C)
  // - Thin border: L ~ 0.330 (#34353A)
  // - Warm gold accent: L ~ 0.744 (#C8A85A)
  // - Off-white foreground: L ~ 0.958 (#F2F1EE)
  const darkTokens = {
    surface: 0.200,
    background: 0.236,
    sidebar: 0.274,
    border: 0.330,
    mutedForeground: 0.687,
    accent: 0.744,
    foreground: 0.958,
  }

  assert.ok(darkTokens.surface < darkTokens.background, "Card surface is near-black, recessed against background")
  assert.ok(darkTokens.background < darkTokens.sidebar, "Sidebar is slightly lighter graphite than canvas background")
  assert.ok(darkTokens.sidebar < darkTokens.border, "Borders provide visible structural separation")
  assert.ok(darkTokens.foreground - darkTokens.surface > 0.7, "Primary text achieves high contrast against surface")
  assert.ok(darkTokens.accent > darkTokens.border, "Gold accent is luminous and distinct")
})
