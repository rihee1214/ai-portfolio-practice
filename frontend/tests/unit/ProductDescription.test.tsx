import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useRef } from 'react'
import { expect, test, vi } from 'vitest'
import { ProductDescriptionEditor, type DescriptionSubmission, type EditorDraft } from '../../src/catalog/ProductDescriptionEditor'
import { ProductDescriptionView } from '../../src/catalog/ProductDescriptionView'
import { descriptionValid } from '../../src/catalog/ProductDescription'

const asset = '00000000-0000-4000-8000-000000000001'

test('rich descriptions render literal strings and use the local media URL', () => {
  const rich = [
    { kind: 'heading', runs: [{ text: '  <strong>제목</strong>  ', bold: true, italic: false }] },
    { kind: 'image', assetId: asset, alt: '설명 사진' },
    { kind: 'paragraph', runs: [{ text: '문단\n두 줄', bold: false, italic: true }] },
  ] as const
  expect(descriptionValid(rich)).toBe(true)
  if (!descriptionValid(rich)) throw new Error('Invalid fixture')
  const { container } = render(<ProductDescriptionView plain="호환용 설명" rich={rich} />)
  expect(screen.getByRole('img', { name: '설명 사진' })).toHaveAttribute('src', '/api/media/' + asset + '/content')
  expect(container.querySelector('h3 strong')?.textContent).toBe('  <strong>제목</strong>  ')
  expect(container.querySelectorAll('strong')).toHaveLength(1)
  expect(container.querySelector('p em')?.textContent).toBe('문단\n두 줄')
  expect(descriptionValid([{ kind: 'image', assetId: 'https://external.test/image.png', alt: '외부 사진' }])).toBe(false)
})

test('the seller can restore an unsaved local image and submit it with the initial draft', async () => {
  if (!URL.createObjectURL) Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: () => '' })
  if (!URL.revokeObjectURL) Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: () => undefined })
  const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:pending-product-photo')
  const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
  let draft: EditorDraft | undefined
  let submitted: DescriptionSubmission | null | undefined
  const file = new File(['test image data'], 'draft.png', { type: 'image/png' })
  function TestEditor({ initial }: { initial?: EditorDraft }) {
    const editor = useRef<(() => DescriptionSubmission | null) | null>(null)
    return <>
      <ProductDescriptionEditor plain="" disabled={false} editorRef={editor} draft={initial} onDraft={value => { draft = value }} />
      <button onClick={() => { submitted = editor.current?.() }}>저장</button>
    </>
  }
  const user = userEvent.setup()
  const first = render(<TestEditor />)
  const surface = screen.getByRole('textbox', { name: '상품 설명' })
  surface.textContent = '먼저 보이는 문장'
  fireEvent.input(surface)
  await user.click(screen.getByRole('button', { name: '이미지 삽입' }))
  await user.upload(screen.getByLabelText('이미지 파일'), file)
  await user.type(screen.getByLabelText('이미지 대체 텍스트'), '임시 설명 사진')
  await user.click(screen.getByRole('button', { name: '설명에 이미지 추가' }))
  first.unmount()
  expect(revoke).toHaveBeenCalledWith('blob:pending-product-photo')
  render(<TestEditor initial={draft} />)
  const restored = screen.getByRole('textbox', { name: '상품 설명' })
  expect(restored).toHaveTextContent('먼저 보이는 문장')
  expect(within(restored).getByRole('img', { name: '임시 설명 사진' })).toHaveAttribute('src', 'blob:pending-product-photo')
  expect(create).toHaveBeenCalledTimes(2)
  await user.click(screen.getByRole('button', { name: '저장' }))
  expect(submitted?.images).toEqual([file])
  expect(submitted?.descriptionBlocks?.[1]).toEqual({ kind: 'image', uploadIndex: 0, alt: '임시 설명 사진' })
  await user.click(within(restored).getByRole('button', { name: '이미지 삭제: 임시 설명 사진' }))
  await user.click(screen.getByRole('button', { name: '저장' }))
  expect(submitted?.images).toEqual([])
  create.mockRestore()
  revoke.mockRestore()
})
