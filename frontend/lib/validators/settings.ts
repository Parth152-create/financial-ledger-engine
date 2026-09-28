import { ApiError } from "@/types/api"

export interface PasswordFormValues {
  password: string
  confirmPassword: string
}

export interface PasswordValidationResult {
  isValid: boolean
  errors: {
    password?: string
    confirmPassword?: string
    general?: string
  }
}

/**
 * Validates password strength according to the engine's security policy:
 * - 8 to 128 characters
 * - At least one letter (a-z, A-Z)
 * - At least one digit (0-9)
 * - Matching confirmation password
 */
export function validatePasswordForm(values: PasswordFormValues): PasswordValidationResult {
  const errors: PasswordValidationResult["errors"] = {}

  const password = values.password || ""
  const confirmPassword = values.confirmPassword || ""

  if (!password) {
    errors.password = "Password is required."
  } else if (password.length < 8 || password.length > 128) {
    errors.password = "Password must be between 8 and 128 characters."
  } else {
    const hasLetter = /[a-zA-Z]/.test(password)
    const hasDigit = /[0-9]/.test(password)
    if (!hasLetter || !hasDigit) {
      errors.password = "Password must contain at least one letter and at least one number."
    }
  }

  if (!confirmPassword) {
    errors.confirmPassword = "Password confirmation is required."
  } else if (password && password !== confirmPassword) {
    errors.confirmPassword = "Passwords do not match."
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  }
}

/**
 * Maps settings and authentication API errors to safe, user-friendly messages
 * without leaking internal stack traces, SQL, Hibernate, Redis, or Spring details.
 */
export function getSettingsErrorMessage(error: unknown): string {
  if (error instanceof ApiError || (typeof error === "object" && error !== null && "status" in error)) {
    const apiErr = error as ApiError
    const status = apiErr.status
    const rawMessage = apiErr.message || apiErr.error || ""

    const containsTechnicalLeak =
      rawMessage.includes("Exception") ||
      rawMessage.includes("org.springframework") ||
      rawMessage.includes("com.parth") ||
      rawMessage.includes("SQL") ||
      rawMessage.includes("StackTrace") ||
      rawMessage.includes("Hibernate") ||
      rawMessage.includes("Redis") ||
      rawMessage.includes("postgres") ||
      rawMessage.includes("deadlock")

    if (status === 401) {
      return "Your session has expired. Please sign in again to continue."
    }
    if (status === 403) {
      return "Access denied: you do not have permission to perform this action."
    }
    if (status === 404) {
      return "User account could not be found."
    }
    if (status === 409) {
      return "A credential conflict occurred. Please try again."
    }
    if (status === 422 || status === 400) {
      if (rawMessage.toLowerCase().includes("password") && !containsTechnicalLeak) {
        return rawMessage
      }
      return "Invalid request. Ensure the password is between 8 and 128 characters and contains at least one letter and one number."
    }
    if (status === 429) {
      return "Too many requests. Please wait a moment before trying again."
    }
    if (
      status === 0 ||
      apiErr.error === "NetworkError" ||
      rawMessage.toLowerCase().includes("network") ||
      rawMessage.toLowerCase().includes("failed to fetch")
    ) {
      return "Network connection failed. Please check your internet connection and try again."
    }
    if (status >= 500 || containsTechnicalLeak) {
      return "A server error occurred. Please try again shortly."
    }
    if (rawMessage.trim()) {
      return rawMessage
    }
  }

  if (error instanceof Error) {
    const msg = error.message.toLowerCase()
    if (msg.includes("network") || msg.includes("failed to fetch")) {
      return "Network connection failed. Please check your internet connection and try again."
    }
  }

  return "An unexpected error occurred. Please try again."
}
