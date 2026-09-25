import { apiClient } from "@/lib/api/client"
import { API_ROUTES } from "@/constants/routes"
import type { User } from "@/types/auth"

export const authApi = {
  getCurrentUser(): Promise<User> {
    return apiClient.get<User>(API_ROUTES.AUTH_ME)
  },

  login(credentials: { email: string; password: string }): Promise<User> {
    return apiClient.post<User>(API_ROUTES.AUTH_LOGIN, credentials)
  },

  signup(data: { name: string; email: string; password: string }): Promise<User> {
    return apiClient.post<User>(API_ROUTES.AUTH_SIGNUP, data)
  },

  linkPassword(password: string): Promise<void> {
    return apiClient.post<void>(API_ROUTES.AUTH_LINK_PASSWORD, { password })
  },

  getGoogleOAuthUrl(): string {
    return `${apiClient.getBaseUrl()}${API_ROUTES.OAUTH2_GOOGLE}`
  },

  logout(): Promise<void> {
    return apiClient.post<void>(API_ROUTES.LOGOUT)
  },
}
