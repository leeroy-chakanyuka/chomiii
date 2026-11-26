import { mkdtemp, realpath, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { spawn } from 'node:child_process'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

if (!process.env.GEMINI_API_KEY) {
  console.error('GEMINI_API_KEY is required for this live benchmark.')
  process.exit(1)
}

const root = await realpath(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..'))
const results = await mkdtemp(path.join(tmpdir(), 'chomiii-m3-results-'))
const data = await mkdtemp(path.join(tmpdir(), 'chomiii-m3-work-'))
const samples = '3' // One warmup and three measured calls per path: at most 12 generation attempts.
const model = 'gemini-3.5-flash-lite'
const instruction = 'You are Chomiii, also called Chomi, a helpful general-purpose assistant. Be warm, clear, and honest. Admit uncertainty. You have no tools, live browsing, or long-term memory.'

async function port() {
  return new Promise((resolve, reject) => {
    const server = createServer().once('error', reject)
    server.listen(0, '127.0.0.1', () => { const value = server.address().port; server.close(() => resolve(value)) })
  })
}

async function run(cwd, args, env) {
  return new Promise(resolve => {
    const child = spawn(process.execPath, args, { cwd, env: { ...process.env, ...env }, stdio: 'ignore' })
    child.once('error', () => resolve(false))
    child.once('exit', code => resolve(code === 0))
  })
}

let app
let appError = false
const timings = []
// Retain only known timing records, not arbitrary application logs or responses.
const timingPattern = /^(?:gemini_timing model=gemini-3\.5-flash-lite client_init_ms=-?\d+ request_open_ms=-?\d+ first_text_ms=-?\d+ total_ms=-?\d+ outcome=(?:complete|cancelled|transport_error|failed|upstream_(?:INVALID_KEY|INVALID_REQUEST|UNSUPPORTED_MODEL|RATE_LIMITED|TIMEOUT|UNAVAILABLE|EMPTY_RESPONSE|CANCELLED))|chat_latency provider=gemini model=gemini-3\.5-flash-lite outcome=(?:complete|interrupted|failed) store_ms=-?\d+ context_ms=-?\d+ queue_ms=-?\d+ first_delta_ms=-?\d+ first_sse_ms=-?\d+ finish_ms=-?\d+ total_ms=-?\d+)$/
function collect(stream) {
  let pending = ''
  stream.on('data', chunk => {
    pending += chunk.toString()
    const lines = pending.split(/\r?\n/)
    pending = lines.pop().slice(-4096)
    for (const line of lines) {
      const match = line.match(/(?:gemini_timing |chat_latency ).*$/)
      if (match && timingPattern.test(match[0])) timings.push(match[0])
    }
  })
}

try {
  const backendPort = await port()
  let frontendPort = await port()
  while (frontendPort === backendPort) frontendPort = await port()
  const origin = `http://127.0.0.1:${frontendPort}`
  const appEnv = { ...process.env, CHOMIII_DATA_DIR: data, CHOMIII_NO_BROWSER: '1',
    CHOMIII_BACKEND_PORT: String(backendPort), CHOMIII_FRONTEND_PORT: String(frontendPort) }
  delete appEnv.GEMINI_API_KEY
  delete appEnv.GOOGLE_API_KEY
  app = spawn(process.execPath, [path.join(root, 'tools/launcher/start.mjs')], {
    cwd: root, env: appEnv, stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
  })
  app.once('error', () => { appError = true })
  collect(app.stdout)
  collect(app.stderr)
  let ready = false
  for (let i = 0; i < 540; i++) {
    if (appError || app.exitCode !== null || app.signalCode !== null) throw new Error('Local application stopped during startup.')
    try { if ((await fetch(`${origin}/api/health`, { signal: AbortSignal.timeout(2000) })).ok) { ready = true; break } } catch {}
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  if (!ready) throw new Error('Local application startup timed out.')
  console.log('Isolated application ready; running browser and API samples.')
  const browserOk = await run(path.join(root, 'frontend'), [path.join(root, 'frontend/node_modules/@playwright/test/cli.js'),
    'test', '-c', 'playwright.benchmark.config.ts', '--output', path.join(data, 'playwright-output'), '--workers', '1', '--timeout', '300000'], {
    CHOMIII_BENCH_UI: origin, CHOMIII_RUN_BROWSER_BENCH: 'true',
    CHOMIII_BENCH_OUTPUT: path.join(results, 'browser.json'), CHOMIII_BENCH_SAMPLES: samples,
  })
  console.log(`Browser benchmark: ${browserOk ? 'passed' : 'failed'}.`)
  const apiOk = await run(root, [path.join(root, 'tools/bench/ttft.mjs')], {
    CHOMIII_BENCH_API: `${origin}/api`, CHOMIII_BENCH_ORIGIN: origin,
    CHOMIII_BENCH_OUTPUT: path.join(results, 'api.json'), CHOMIII_BENCH_SAMPLES: samples,
    CHOMIII_BENCH_MODEL: model, CHOMIII_BENCH_INSTRUCTION: instruction,
  })
  console.log(`API benchmark: ${apiOk ? 'passed' : 'failed'}.`)
  if (!browserOk || !apiOk || appError || app.exitCode !== null || app.signalCode !== null) process.exitCode = 1
} catch (error) {
  console.error(`Benchmark failed: ${error.message}`)
  process.exitCode = 1
} finally {
  if (app && !appError && app.exitCode === null && app.signalCode === null) {
    const exited = new Promise(resolve => app.once('exit', resolve))
    if (app.connected) app.send('stop')
    else app.kill()
    await exited
  }
  try { await writeFile(path.join(results, 'stages.txt'), timings.join('\n') + (timings.length ? '\n' : '')) }
  catch { console.error('Could not write timing results.'); process.exitCode = 1 }
  try { await rm(data, { recursive: true, force: true }) }
  catch { console.error('Could not remove temporary application data.'); process.exitCode = 1 }
  console.log(`Sanitized benchmark results: ${results}`)
}
