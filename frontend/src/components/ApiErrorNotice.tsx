import type { ApiErrorView } from '../api/apiError'

export function ApiErrorNotice({ error }: { error: ApiErrorView }) {
  return (
    <div className="api-error-notice" role="alert" aria-atomic="true">
      <p>{error.message}</p>
      {error.requestId && <p className="request-id">문의할 때 알려주세요. 요청 ID: <code>{error.requestId}</code></p>}
    </div>
  )
}
