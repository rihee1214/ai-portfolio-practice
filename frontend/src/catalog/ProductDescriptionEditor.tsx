import { useContext, useEffect, useRef, useState, useSyncExternalStore, type RefObject } from 'react'
import { MAX_DESCRIPTION_BYTES, MAX_DESCRIPTION_IMAGES, plainDescription, type DescriptionBlock, type DescriptionDraftBlock } from './ProductDescription'
import { bookmarkCaret, bookmarkedRange, collectEditor, editorSelection, imageNode, loadEditor, restoreSelection, type EditorCaret } from './descriptionEditorDom'
import { ProductDraftContext } from './ProductDraftContext'
import './product-description.css'

export type DescriptionSubmission = { description: string; descriptionBlocks?: DescriptionDraftBlock[]; images: File[] }
export type EditorDraft = {
  blocks: DescriptionDraftBlock[] | null
  files: [number, File][]
  caret?: EditorCaret
  picker: { adding: boolean; file: File | null; alt: string }
}
const subscribeEmpty = () => () => undefined
const noFile = () => null

export function ProductDescriptionEditor({ plain, rich, disabled, editorRef, draft, onDraft }: {
  plain: string
  rich?: DescriptionBlock[] | null
  disabled: boolean
  editorRef: RefObject<(() => DescriptionSubmission | null) | null>
  draft?: EditorDraft
  onDraft: (draft: EditorDraft) => void
}) {
  const [initial] = useState({ plain, rich, draft })
  const scope = useContext(ProductDraftContext)
  const editor = useRef<HTMLDivElement>(null)
  const saved = useRef<Range | null>(null)
  const files = useRef(new Map<number, { file: File; url: string }>())
  const nextUpload = useRef(Math.max(-1, ...(draft?.files.map(([index]) => index) ?? [])) + 1)
  const changed = useRef(Boolean(draft?.blocks))
  const [picker, setPicker] = useState(draft?.picker ?? { adding: false, file: null as File | null, alt: '' })
  const { adding, alt } = picker
  const pickedFile = useSyncExternalStore(scope?.subscribeFile ?? subscribeEmpty, scope?.getFile ?? noFile)
  const file = scope ? pickedFile : picker.file
  const [error, setError] = useState('')

  useEffect(() => {
    const pending = files.current
    for (const [index, file] of initial.draft?.files ?? []) {
      pending.set(index, { file, url: URL.createObjectURL(file) })
    }
    if (editor.current) {
      loadEditor(editor.current, initial.plain, initial.draft?.blocks ?? initial.rich, pending)
      saved.current = bookmarkedRange(editor.current, initial.draft?.caret)
    }
    return () => {
      pending.forEach(image => URL.revokeObjectURL(image.url))
      pending.clear()
    }
  }, [initial])

  useEffect(() => {
    editorRef.current = () => {
      if (!editor.current) return null
      if (adding && (file || alt.trim())) {
        setError('선택한 이미지를 추가하거나 취소한 뒤 저장해 주세요.')
        return null
      }
      if (!changed.current) return { description: plain, images: [] }
      const blocks = collectEditor(editor.current)
      const last = blocks.at(-1)
      if (last?.kind === 'paragraph' && !last.runs.length && blocks.length === 1) blocks.pop()
      const description = plainDescription(blocks)
      if ([...description].length > 4000 || blocks.length > 100
        || blocks.reduce((count, block) => count + (block.kind === 'image' ? 0 : block.runs.length), 0) > 256) {
        setError('설명은 4000자와 문단 100개 이내로 입력해 주세요.')
        return null
      }
      const images: File[] = []
      const descriptionBlocks = blocks.map(block => {
        if (block.kind !== 'image' || !('uploadIndex' in block)) return block
        const pending = files.current.get(block.uploadIndex)
        if (!pending) return null
        images.push(pending.file)
        return { kind: 'image' as const, uploadIndex: images.length - 1, alt: block.alt }
      })
      const resolved = descriptionBlocks.filter(block => block !== null)
      if (resolved.length !== blocks.length) {
        setError('이미지를 읽지 못했습니다. 다시 선택해 주세요.')
        return null
      }
      setError('')
      return { description, descriptionBlocks: resolved, images }
    }
    return () => { editorRef.current = null }
  }, [editorRef, plain, adding, file, alt])

  function remember() {
    if (!editor.current) return
    const range = editorSelection(editor.current)
    if (range) saved.current = range.cloneRange()
  }

  function persist(next = picker) {
    if (!editor.current) return
    const blocks = changed.current ? collectEditor(editor.current) : null
    const used = new Set(blocks?.flatMap(block => 'uploadIndex' in block ? [block.uploadIndex] : []))
    onDraft({
      blocks,
      files: [...files.current].filter(([index]) => used.has(index)).map(([index, image]) => [index, image.file]),
      caret: bookmarkCaret(editor.current, saved.current),
      picker: next,
    })
  }

  function updatePicker(next: typeof picker) {
    setPicker(next)
    persist(next)
  }

  function format(command: string, value?: string) {
    if (!editor.current) return
    restoreSelection(editor.current, saved.current)
    document.execCommand(command, false, value)
    changed.current = true
    remember()
    persist()
  }

  function cancelImage() {
    scope?.setFile(null)
    updatePicker({ adding: false, file: null, alt: '' })
    setError('')
  }

  function addImage() {
    if (!editor.current || !file) { setError('이미지 파일을 선택해 주세요.'); return }
    if (!/\.(png|jpe?g|webp)$/i.test(file.name)) { setError('PNG, JPG 또는 WebP 이미지를 선택해 주세요.'); return }
    const label = alt.trim()
    if (!label || [...label].length > 120 || [...label].some(char => {
      const point = char.codePointAt(0)!
      return point < 32 || point >= 127 && point <= 159
    })) {
      setError('이미지를 설명하는 대체 텍스트를 1~120자로 입력해 주세요.'); return
    }
    const current = collectEditor(editor.current).filter(block => block.kind === 'image')
    const bytes = current.reduce((size, block) => size + ('uploadIndex' in block ? files.current.get(block.uploadIndex)?.file.size ?? 0 : 0), file.size)
    if (current.length >= MAX_DESCRIPTION_IMAGES || bytes > MAX_DESCRIPTION_BYTES) {
      setError('이미지는 최대 5장, 한 번에 총 5MB까지 추가할 수 있습니다.'); return
    }
    const index = nextUpload.current++
    const url = URL.createObjectURL(file)
    files.current.set(index, { file, url })
    const figure = imageNode(url, label, { uploadIndex: index })
    const range = restoreSelection(editor.current, saved.current)
    range.collapse(false)
    range.insertNode(figure)
    range.setStartAfter(figure)
    range.collapse(true)
    changed.current = true
    remember()
    cancelImage()
  }

  return <div className="description-editor">
    <span id="product-description-label">상품 설명</span>
    <p id="product-description-hint">4000자 이내. 이미지는 커서 위치에 추가되며 상품을 저장할 때 업로드됩니다.</p>
    <div role="group" aria-label="상품 설명 서식" className="description-editor-toolbar" onMouseDown={event => event.preventDefault()}>
      <button type="button" onClick={() => format('bold')}>굵게</button>
      <button type="button" onClick={() => format('italic')}>기울임</button>
      <button type="button" onClick={() => format('formatBlock', 'h3')}>소제목</button>
      <button type="button" onClick={() => format('formatBlock', 'p')}>본문</button>
      <button type="button" aria-expanded={adding} onClick={() => { remember(); updatePicker({ ...picker, adding: true }); setError('') }}>이미지 삽입</button>
    </div>
    <div id="product-description" className="description-editor-surface" role="textbox" aria-multiline="true"
      aria-labelledby="product-description-label" aria-describedby="product-description-hint" aria-readonly={disabled}
      ref={editor} contentEditable={!disabled} onInput={() => { changed.current = true; remember(); persist() }}
      onSelect={remember} onBlur={remember} onMouseUp={remember} onKeyUp={remember}
      onDrop={event => event.preventDefault()}
      onPaste={event => {
        event.preventDefault()
        if (disabled) return
        const text = event.clipboardData.getData('text/plain').replace(/\r\n?/g, '\n')
        if (!text) return
        changed.current = true
        if (!document.execCommand('insertText', false, text)) {
          const range = restoreSelection(event.currentTarget, editorSelection(event.currentTarget))
          range.deleteContents()
          const node = document.createTextNode(text)
          range.insertNode(node)
          range.setStartAfter(node)
          range.collapse(true)
        }
        remember()
        persist()
      }}
      onClick={event => {
        if (disabled || !(event.target instanceof HTMLElement) || !event.target.closest('[data-remove-image]')) return
        const figure = event.target.closest('figure')
        if (!figure) return
        const pendingIndex = figure.dataset.uploadIndex
        if (pendingIndex !== undefined) {
          const pending = files.current.get(Number(pendingIndex))
          if (pending) URL.revokeObjectURL(pending.url)
          files.current.delete(Number(pendingIndex))
        }
        const caret = document.createRange()
        caret.setStartBefore(figure)
        caret.collapse(true)
        figure.remove()
        changed.current = true
        restoreSelection(event.currentTarget, caret)
        remember()
        persist()
      }}
    />
    {adding && <div className="description-image-picker">
      {scope ? <div><button autoFocus type="button" onClick={scope.openFilePicker}>이미지 파일 선택</button></div>
        : <label>이미지 파일<input autoFocus type="file" accept=".png,.jpg,.jpeg,.webp" onChange={event => updatePicker({ ...picker, file: event.target.files?.[0] ?? null })} /></label>}
      {file && <small>{file.name}</small>}
      <label>이미지 대체 텍스트<input value={alt} onChange={event => updatePicker({ ...picker, alt: event.target.value })} /></label>
      <div><button type="button" onClick={addImage}>설명에 이미지 추가</button> <button type="button" onClick={cancelImage}>취소</button></div>
    </div>}
    {error && <p role="alert">{error}</p>}
  </div>
}
