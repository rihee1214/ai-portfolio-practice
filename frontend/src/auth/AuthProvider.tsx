import { useEffect, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router'
import { AuthContext } from './AuthContext'
import { AuthStore } from './AuthStore'
export default function AuthProvider({ children }: { children: ReactNode }) {
  const [store] = useState(() => new AuthStore())
  const location = useLocation()
  useEffect(() => { store.invalidate() }, [store, location.key])
  useEffect(() => {
    const onFocus = () => { void store.refresh() }
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') store.stop()
      else store.invalidate()
    }
    const onPageShow = () => store.invalidate()
    const channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('marketplace-auth')
    if (channel) channel.onmessage = event => {
      if (event.data?.source !== store.source) store.invalidate()
    }
    window.addEventListener('focus', onFocus)
    window.addEventListener('pageshow', onPageShow)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      store.stop()
      channel?.close()
      window.removeEventListener('focus', onFocus)
      window.removeEventListener('pageshow', onPageShow)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [store])
  return <AuthContext value={store}>{children}</AuthContext>
}

