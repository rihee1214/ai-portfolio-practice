import { createContext } from 'react'
import type { CartStore } from './CartStore'
export const CartContext = createContext<CartStore | null>(null)
