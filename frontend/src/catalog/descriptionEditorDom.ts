import { descriptionImageUrl, type DescriptionDraftBlock, type DescriptionRun, type DescriptionText } from './ProductDescription'

export function imageNode(url: string, alt: string, reference: { assetId: string } | { uploadIndex: number }) {
  const figure = document.createElement('figure')
  figure.className = 'description-editor-image'
  figure.contentEditable = 'false'
  if ('assetId' in reference) figure.dataset.assetId = reference.assetId
  else figure.dataset.uploadIndex = String(reference.uploadIndex)
  const image = document.createElement('img')
  image.src = url
  image.alt = alt
  image.draggable = false
  const remove = document.createElement('button')
  remove.type = 'button'
  remove.textContent = '이미지 삭제'
  remove.setAttribute('aria-label', '이미지 삭제: ' + alt)
  remove.dataset.removeImage = ''
  figure.append(image, remove)
  return figure
}

export function loadEditor(editor: HTMLElement, plain: string, rich: DescriptionDraftBlock[] | null | undefined, uploads: Map<number, { url: string }>) {
  const blocks = rich ?? (plain ? [{ kind: 'paragraph' as const, runs: [{ text: plain, bold: false, italic: false }] }] : [])
  editor.replaceChildren(...blocks.map(block => {
    if (block.kind === 'image') {
      if ('assetId' in block) return imageNode(descriptionImageUrl(block.assetId, true), block.alt, block)
      const upload = uploads.get(block.uploadIndex)
      return upload ? imageNode(upload.url, block.alt, block) : document.createTextNode('')
    }
    const element = document.createElement(block.kind === 'heading' ? 'h3' : 'p')
    for (const run of block.runs) {
      let text: Node = document.createTextNode(run.text)
      if (run.italic) { const mark = document.createElement('em'); mark.append(text); text = mark }
      if (run.bold) { const mark = document.createElement('strong'); mark.append(text); text = mark }
      element.append(text)
    }
    if (!element.hasChildNodes()) element.append(document.createElement('br'))
    return element
  }))
}

export function collectEditor(editor: HTMLElement): DescriptionDraftBlock[] {
  const blocks: DescriptionDraftBlock[] = []
  let runs: DescriptionRun[] = []
  let kind: DescriptionText['kind'] = 'paragraph'
  const flush = (empty = false) => {
    if (runs.length || empty) blocks.push({ kind, runs })
    runs = []
  }
  const append = (text: string, bold: boolean, italic: boolean) => {
    if (!text) return
    const last = runs.at(-1)
    if (last && last.bold === bold && last.italic === italic) last.text += text
    else runs.push({ text, bold, italic })
  }
  function walk(node: Node, bold = false, italic = false) {
    if (node.nodeType === Node.TEXT_NODE) { append(node.textContent ?? '', bold, italic); return }
    if (!(node instanceof HTMLElement)) return
    if (node.tagName === 'FIGURE') {
      const alt = node.querySelector('img')?.alt
      if (!alt) return
      flush()
      if (node.dataset.assetId) blocks.push({ kind: 'image', assetId: node.dataset.assetId, alt })
      else if (node.dataset.uploadIndex !== undefined) blocks.push({ kind: 'image', uploadIndex: Number(node.dataset.uploadIndex), alt })
      return
    }
    if (node.tagName === 'BR') {
      if (node.nextSibling) append('\n', bold, italic)
      return
    }
    const textBlock = /^(DIV|P|H[1-6])$/.test(node.tagName)
    const original = kind
    if (textBlock) {
      flush()
      kind = node.tagName.startsWith('H') ? 'heading' : 'paragraph'
    }
    const start = blocks.length
    const weight = node.style.fontWeight
    const style = node.style.fontStyle
    node.childNodes.forEach(child => walk(child,
      weight ? weight === 'bold' || Number(weight) >= 600 : bold || node.tagName === 'B' || node.tagName === 'STRONG',
      style ? style === 'italic' : italic || node.tagName === 'I' || node.tagName === 'EM'))
    if (textBlock) { flush(start === blocks.length); kind = original }
  }
  editor.childNodes.forEach(node => walk(node))
  flush()
  return blocks
}

export function editorSelection(editor: HTMLElement) {
  const selection = window.getSelection()
  if (!selection?.rangeCount) return null
  const range = selection.getRangeAt(0)
  return editor.contains(range.startContainer) && editor.contains(range.endContainer) ? range : null
}

export type EditorCaret = { block: number; offset: number | null }

export function bookmarkCaret(editor: HTMLElement, saved: Range | null): EditorCaret | undefined {
  if (!saved || !editor.contains(saved.endContainer)) return undefined
  const prefix = document.createRange()
  prefix.setStart(editor, 0)
  prefix.setEnd(saved.endContainer, saved.endOffset)
  const content = document.createElement('div')
  content.append(prefix.cloneContents())
  const blocks = collectEditor(content)
  const last = blocks.at(-1)
  return {
    block: blocks.length - 1,
    offset: last && last.kind !== 'image' ? last.runs.reduce((length, run) => length + run.text.length, 0) : null,
  }
}

export function bookmarkedRange(editor: HTMLElement, caret: EditorCaret | undefined) {
  if (!caret) return null
  const range = document.createRange()
  const block = editor.childNodes[caret.block]
  if (!block) { range.setStart(editor, 0); return range }
  if (caret.offset === null) { range.setStartAfter(block); range.collapse(true); return range }
  const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT)
  let remaining = caret.offset
  for (let text = walker.nextNode(); text; text = walker.nextNode()) {
    const length = text.textContent?.length ?? 0
    if (remaining <= length) { range.setStart(text, remaining); range.collapse(true); return range }
    remaining -= length
  }
  range.selectNodeContents(block)
  range.collapse(false)
  return range
}

export function restoreSelection(editor: HTMLElement, saved: Range | null) {
  editor.focus()
  const range = saved && editor.contains(saved.startContainer) && editor.contains(saved.endContainer) ? saved : document.createRange()
  if (range !== saved) { range.selectNodeContents(editor); range.collapse(false) }
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
  return range
}
