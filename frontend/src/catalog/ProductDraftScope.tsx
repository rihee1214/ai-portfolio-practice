import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useAuthStore } from '../auth/AuthContext'
import { ProductDraftContext, type ProductPageDraft } from './ProductDraftContext'

export function ProductDraftScope({ children }: { children: ReactNode }) {
  const auth = useAuthStore()
  const input = useRef<HTMLInputElement>(null)
  const [draft] = useState(() => {
    let current: ProductPageDraft | null = null
    let picked: File | null = null
    const listeners = new Set<() => void>()
    const setFile = (file: File | null) => {
      picked = file
      listeners.forEach(listener => listener())
    }
    return {
      get: () => current,
      set: (next: ProductPageDraft | null) => {
        current = next
        if (!next?.form.editor?.picker.adding && picked) setFile(null)
      },
      getFile: () => picked,
      setFile,
      subscribeFile: (listener: () => void) => {
        listeners.add(listener)
        return () => { listeners.delete(listener) }
      },
    }
  })
  useEffect(() => auth.subscribe(() => {
    const session = auth.getSnapshot()
    if (session.checked && (draft.get()?.owner !== session.member?.id || !session.member?.roles.includes('SELLER'))) {
      draft.set(null)
    }
  }), [auth, draft])
  return <ProductDraftContext value={{
    ...draft,
    openFilePicker: () => input.current?.click(),
  }}>
    {children}
    {/* Keep the OS picker attached while the workspace rechecks the current session on focus. */}
    <input hidden ref={input} type="file" aria-label="이미지 파일" accept=".png,.jpg,.jpeg,.webp" onChange={event => {
      if (draft.get()?.form.editor?.picker.adding) draft.setFile(event.target.files?.[0] ?? null)
      event.target.value = ''
    }} />
  </ProductDraftContext>
}
