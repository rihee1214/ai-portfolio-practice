import { createContext } from 'react'
import type { Product } from './CatalogStore'
import type { EditorDraft } from './ProductDescriptionEditor'

export type ProductFormDraft = {
  slug?: string
  title?: string
  returnFee?: string
  categoryId?: string
  state?: Product['state']
  confirmed?: boolean
  editor?: EditorDraft
}

export type ProductPageDraft = {
  owner: string
  page: number
  selected: string | null
  version?: number
  pinned?: Product
  form: ProductFormDraft
}

export const ProductDraftContext = createContext<{
  get: () => ProductPageDraft | null
  set: (draft: ProductPageDraft | null) => void
  getFile: () => File | null
  setFile: (file: File | null) => void
  subscribeFile: (listener: () => void) => () => void
  openFilePicker: () => void
} | null>(null)
