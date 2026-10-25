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
