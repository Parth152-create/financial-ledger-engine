"use client"

import { useRouter } from "next/navigation"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { authApi } from "@/lib/api/auth"
import { ROUTES } from "@/constants/routes"
import type { User } from "@/types/auth"

export const AUTH_QUERY_KEY = ["auth", "me"] as const

export function useAuth() {
  const router = useRouter()
  const queryClient = useQueryClient()

  const { data: user, isLoading, error, refetch } = useQuery<User>({
    queryKey: AUTH_QUERY_KEY,
    queryFn: () => authApi.getCurrentUser(),
    staleTime: 5 * 60 * 1000,
    retry: false,
  })

  const loginWithGoogle = () => {
    window.location.href = authApi.getGoogleOAuthUrl()
  }

  const loginWithEmail = async (email: string, password: string): Promise<User> => {
    const authenticatedUser = await authApi.login({ email, password })
    queryClient.setQueryData(AUTH_QUERY_KEY, authenticatedUser)
    await queryClient.invalidateQueries({ queryKey: AUTH_QUERY_KEY })
    router.push(ROUTES.DASHBOARD)
    return authenticatedUser
  }

  const signupWithEmail = async (name: string, email: string, password: string): Promise<User> => {
    const createdUser = await authApi.signup({ name, email, password })
    queryClient.setQueryData(AUTH_QUERY_KEY, createdUser)
    await queryClient.invalidateQueries({ queryKey: AUTH_QUERY_KEY })
    router.push(ROUTES.DASHBOARD)
    return createdUser
  }

  const logout = async () => {
    try {
      await authApi.logout()
    } catch {
      // Ignore network errors on logout
    } finally {
      queryClient.setQueryData(AUTH_QUERY_KEY, null)
      queryClient.invalidateQueries({ queryKey: AUTH_QUERY_KEY })
      router.push(ROUTES.LOGIN)
    }
  }

  return {
    user: user ?? null,
    isLoading,
    isAuthenticated: !!user,
    error,
    refetch,
    loginWithGoogle,
    loginWithEmail,
    signupWithEmail,
    logout,
  }
}
