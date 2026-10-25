import { useFormDraft } from './useFormDraft'
export function QuantityForm({ id, label, initial = 1, disabled = false, action, onSubmit, children }: {
  id: string; label: string; initial?: number; disabled?: boolean; action: string; onSubmit: (quantity: number) => void; children?: React.ReactNode
}) {
  const draft = useFormDraft({ quantity: String(initial) })
  return <form noValidate aria-busy={disabled} onSubmit={event => {
    event.preventDefault()
    if (disabled) return
    if (draft.validate(event.currentTarget, v => /^\d+$/.test(v.quantity) && Number(v.quantity) >= 1 && Number(v.quantity) <= 99 ? null : { field: 'quantity', message: '수량은 1~99 사이의 정수로 입력해 주세요.' })) onSubmit(Number(draft.values.quantity))
  }}>
    <label htmlFor={id}>{label}</label><input id={id} type="number" min="1" max="99" step="1" required disabled={disabled} {...draft.input('quantity')} />
    <button disabled={disabled} aria-label={action}>{action}</button>{children}
    {draft.error && <p id={draft.errorId} role="alert">{draft.error.message}</p>}
  </form>
}
