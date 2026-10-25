import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import HomePage from '../../src/components/ShopNamePreview'

test('매대 이름 입력이 미리보기에 반영되고 초기화된다', async () => {
  const user = userEvent.setup()
  render(<HomePage />)
  const input = screen.getByRole('textbox', { name: '매대 이름' })
  await user.clear(input)
  await user.type(input, '우리 매대')
  expect(screen.getByText('우리 매대')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(input).toHaveValue('나의 작은 매대')
  expect(screen.getByText('나의 작은 매대')).toBeVisible()
  expect(fetch).not.toHaveBeenCalled()
})

test('공백 이름은 안내로 표시하고 사용자 입력은 30자로 제한한다', async () => {
  const user = userEvent.setup()
  render(<HomePage />)
  const input = screen.getByRole('textbox', { name: '매대 이름' })
  await user.clear(input)
  await user.type(input, '   ')
  expect(screen.getByText('이름을 기다리는 매대')).toBeVisible()
  await user.clear(input)
  await user.type(input, '가'.repeat(31))
  expect(input).toHaveValue('가'.repeat(30))
  expect(screen.getByText('가'.repeat(30))).toBeVisible()
})

test('다시 마운트하면 저장되지 않은 이름이 초기화된다', async () => {
  const user = userEvent.setup()
  const first = render(<HomePage />)
  await user.clear(screen.getByRole('textbox', { name: '매대 이름' }))
  await user.type(screen.getByRole('textbox', { name: '매대 이름' }), '임시 이름')
  first.unmount()
  render(<HomePage />)
  expect(screen.getByRole('textbox', { name: '매대 이름' })).toHaveValue('나의 작은 매대')
  expect(fetch).not.toHaveBeenCalled()
})
