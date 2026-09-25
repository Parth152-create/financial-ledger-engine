import { apiClient } from "@/lib/api/client"
import { API_ROUTES } from "@/constants/routes"
import type { User } from "@/types/auth"

export const authApi = {
  getCurrentUser(): Promise<User> {
    return apiClient.get<User>(API_ROUTES.AUTH_ME)
  },

  getGoogleOAuthUrl(): string {
    return `${apiClient.getBaseUrl()}${API_ROUTES.OAUTH2_GOOGLE}`
  },

  logout(): Promise<void> {
    return apiClient.post<void>(API_ROUTES.LOGOUT)
  },
}
