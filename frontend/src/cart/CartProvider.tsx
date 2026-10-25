import { useEffect, useState, type ReactNode } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { CartContext } from './CartContext'
import { CartStore } from './CartStore'
import { GuestCart } from './GuestCart'
export default function CartProvider({ children }: { children: ReactNode }) {
  const auth = useAuthStore()
  const [store] = useState(() => new CartStore(auth, new GuestCart({ getItem: key => localStorage.getItem(key), setItem: (key, value) => localStorage.setItem(key, value), removeItem: key => localStorage.removeItem(key) })))
  useEffect(() => store.connect(), [store])
  return <CartContext value={store}>{children}</CartContext>
}
