import { useId, useState } from 'react'
type Problem<T> = { field: keyof T; message: string }
// Drafts belong to a mounted form, never to persisted account/cart state.
export function useFormDraft<T extends Record<string, string>>(initial: T) {
  const [values, setValues] = useState(initial)
  const [error, setError] = useState<Problem<T> | null>(null)

  const errorId = useId()
  const change = (field: keyof T, value: string) => { setValues(old => ({ ...old, [field]: value })); setError(null) }
  const validate = (element: HTMLFormElement, check: (values: T) => Problem<T> | null) => {
    const problem = check(values); setError(problem)
    if (problem) (element.elements.namedItem(String(problem.field)) as HTMLElement | null)?.focus()
    return !problem
  }
  const input = (field: keyof T) => ({ name: String(field), value: values[field], onChange: (event: React.ChangeEvent<HTMLInputElement>) => change(field, event.target.value),
    'aria-invalid': error?.field === field ? true : undefined, 'aria-describedby': error?.field === field ? errorId : undefined })
  return { values, input, validate, error, errorId }
}
