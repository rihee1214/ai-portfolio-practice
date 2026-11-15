import { createContext, useContext } from 'react'
import type { RuntimePolicy } from '../api/runtimePolicy'
export type PolicyState = { mode: RuntimePolicy['mode'] | 'pending' | 'unavailable'; restricted: boolean }
// Isolated pre-existing components retain their local contract. The application always installs the provider.
export const RuntimePolicyContext = createContext<PolicyState>({ mode: 'local-full', restricted: false })
export const useRuntimePolicy = () => useContext(RuntimePolicyContext)
