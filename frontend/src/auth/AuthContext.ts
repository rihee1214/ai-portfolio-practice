import { createContext, useContext } from 'react'
import { AuthStore } from './AuthStore'
export const AuthContext = createContext<AuthStore | null>(null)
export function useAuthStore() {
  const store = useContext(AuthContext)
  if (!store) throw new Error('AuthProvider is required')
  return store
}
