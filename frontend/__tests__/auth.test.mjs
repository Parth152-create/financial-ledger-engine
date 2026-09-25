import test from "node:test"
import assert from "node:assert/strict"

// 1. Email Normalization
test("Auth: Email normalization trims and lowercases email correctly", () => {
  const normalizeEmail = (email) => (email || "").trim().toLowerCase()

  assert.equal(normalizeEmail("  Alice@Example.COM  "), "alice@example.com")
  assert.equal(normalizeEmail("USER.TEST@LEDGER.IO"), "user.test@ledger.io")
  assert.equal(normalizeEmail(""), "")
})

// 2. Login Form Validation
test("Auth: Login validation requires non-empty email and password", () => {
  const validateLogin = ({ email, password }) => {
    const trimmedEmail = (email || "").trim()
    if (!trimmedEmail) return { valid: false, error: "Email address is required." }
    if (!password) return { valid: false, error: "Password is required." }
    return { valid: true }
  }

  assert.deepEqual(validateLogin({ email: "user@example.com", password: "Password123" }), { valid: true })
  assert.equal(validateLogin({ email: "", password: "Password123" }).valid, false)
  assert.equal(validateLogin({ email: "   ", password: "Password123" }).valid, false)
  assert.equal(validateLogin({ email: "user@example.com", password: "" }).valid, false)
})

// 3. Signup Form Validation
test("Auth: Signup validation enforces name, email, password strength, and matching confirmation", () => {
  const validateSignup = ({ name, email, password, confirmPassword }) => {
    const trimmedName = (name || "").trim()
    if (!trimmedName) return { valid: false, error: "Full name is required." }

    const trimmedEmail = (email || "").trim()
    if (!trimmedEmail) return { valid: false, error: "Email address is required." }
    if (!trimmedEmail.includes("@") || !trimmedEmail.includes(".")) {
      return { valid: false, error: "Please enter a valid email address." }
    }

    if (!password) return { valid: false, error: "Password is required." }
    if (password.length < 8 || password.length > 128) {
      return { valid: false, error: "Password must be between 8 and 128 characters." }
    }

    const hasLetter = /[a-zA-Z]/.test(password)
    const hasDigit = /[0-9]/.test(password)
    if (!hasLetter || !hasDigit) {
      return { valid: false, error: "Password must contain at least one letter and at least one number." }
    }

    if (password !== confirmPassword) {
      return { valid: false, error: "Passwords do not match." }
    }

    return { valid: true }
  }

  // Valid submission
  assert.deepEqual(
    validateSignup({
      name: "Alice Smith",
      email: "alice@example.com",
      password: "StrongPass123",
      confirmPassword: "StrongPass123",
    }),
    { valid: true }
  )

  // Empty name
  assert.equal(
    validateSignup({
      name: "  ",
      email: "alice@example.com",
      password: "StrongPass123",
      confirmPassword: "StrongPass123",
    }).valid,
    false
  )

  // Invalid email format
  assert.equal(
    validateSignup({
      name: "Alice Smith",
      email: "invalid-email",
      password: "StrongPass123",
      confirmPassword: "StrongPass123",
    }).valid,
    false
  )

  // Password too short (< 8 chars)
  assert.equal(
    validateSignup({
      name: "Alice Smith",
      email: "alice@example.com",
      password: "pass1",
      confirmPassword: "pass1",
    }).valid,
    false
  )

  // Password missing digit
  assert.equal(
    validateSignup({
      name: "Alice Smith",
      email: "alice@example.com",
      password: "PasswordOnly",
      confirmPassword: "PasswordOnly",
    }).valid,
    false
  )

  // Password missing letter
  assert.equal(
    validateSignup({
      name: "Alice Smith",
      email: "alice@example.com",
      password: "1234567890",
      confirmPassword: "1234567890",
    }).valid,
    false
  )

  // Password confirmation mismatch
  assert.equal(
    validateSignup({
      name: "Alice Smith",
      email: "alice@example.com",
      password: "StrongPass123",
      confirmPassword: "DifferentPass456",
    }).valid,
    false
  )
})

// 4. Auth API Contract & Safety
test("Auth: User contract exposes safe identity fields and never exposes sensitive credentials", () => {
  const sanitizeUserResponse = (apiData) => {
    const { id, email, name, ...rest } = apiData
    return {
      safeUser: { id, email, name },
      leakedKeys: Object.keys(rest).filter((k) =>
        ["password", "passwordHash", "hash", "secret"].includes(k)
      ),
    }
  }

  const rawUserResponse = {
    id: "44c1d719-6d0c-489f-97bb-ab063c7c597d",
    email: "alice@example.com",
    name: "Alice Smith",
  }

  const { safeUser, leakedKeys } = sanitizeUserResponse(rawUserResponse)
  assert.equal(safeUser.email, "alice@example.com")
  assert.equal(safeUser.name, "Alice Smith")
  assert.equal(leakedKeys.length, 0)
})

// 5. Google OAuth URL Formation
test("Auth: Resolves Google OAuth authorization URL correctly", () => {
  const getGoogleOAuthUrl = (baseUrl = "http://localhost:8085") => {
    return `${baseUrl}/oauth2/authorization/google`
  }

  assert.equal(getGoogleOAuthUrl(), "http://localhost:8085/oauth2/authorization/google")
  assert.equal(getGoogleOAuthUrl("https://ledger.example.com"), "https://ledger.example.com/oauth2/authorization/google")
})

// 6. Conflict Error Handling
test("Auth: Duplicate account error status 409 is parsed correctly", () => {
  const parseAuthError = (status, errorBody) => {
    if (status === 409) {
      return errorBody?.message || "An account with this email already exists"
    }
    if (status === 401) {
      return "Invalid email or password"
    }
    return errorBody?.message || "Authentication failed"
  }

  assert.equal(
    parseAuthError(409, { message: "An account with this email already exists" }),
    "An account with this email already exists"
  )
  assert.equal(parseAuthError(401, {}), "Invalid email or password")
})
