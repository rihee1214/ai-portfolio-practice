export async function withPhoneResources(server, launch, run) {
  let browser
  try {
    await server.listen()
    browser = await launch()
    return await run(browser)
  } finally {
    try { await browser?.close() }
    finally { await server.close() }
  }
}

// Diagnostics contain only fixed stage names and known network codes, never tokens or messages.
export function phoneProbeFailure(stage, error) {
  const stages = new Set(['BROWSER_LISTEN', 'BROWSER_NAVIGATION', 'BROWSER_SDK', 'EMULATOR_CODES', 'JAVA_ISSUE', 'JAVA_EXCHANGE', 'JAVA_CONFIRM', 'JAVA_BINDING_ISSUE', 'JAVA_BINDING_EXCHANGE', 'JAVA_BINDING_CONFIRM'])
  const names = new Set(['Error', 'TypeError', 'AssertionError', 'TimeoutError', 'AbortError', 'AggregateError'])
  const codes = new Set(['ECONNREFUSED', 'ECONNRESET', 'ETIMEDOUT', 'EADDRINUSE', 'EADDRNOTAVAIL', 'ENOTFOUND', 'EHOSTUNREACH', 'EACCES', 'UND_ERR_SOCKET', 'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_HEADERS_TIMEOUT', 'UND_ERR_BODY_TIMEOUT', 'ERR_INVALID_URL'])
  const cause = error?.cause
  const candidates = [cause, ...(Array.isArray(cause?.errors) ? cause.errors.slice(0, 4) : [])]
  const found = candidates.flatMap(value => codes.has(value?.code) ? [value.code] : value?.message === 'bad port' ? ['FETCH_FORBIDDEN_PORT'] : [])
  return { stage: stages.has(stage) ? stage : 'UNKNOWN', error: names.has(error?.name) ? error.name : 'Error', causes: [...new Set(found)] }
}
