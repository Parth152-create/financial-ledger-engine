export interface User {
  id: string
  email: string
  name: string
}

export interface AuthState {
  user: User | null
  isLoading: boolean
  isAuthenticated: boolean
  error: Error | null
}
