import { mkdtemp, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import { spawn } from 'node:child_process'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const data = await mkdtemp(path.join(tmpdir(), 'chomiii-benchmark-'))
async function port() {
  return new Promise((resolve, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => { const value = server.address().port; server.close(() => resolve(value)) })
  })
}
const backendPort = await port()
const frontendPort = await port()
const origin = `http://127.0.0.1:${frontendPort}`
const env = { ...process.env, CHOMIII_DATA_DIR: data, CHOMIII_NO_BROWSER: '1',
  CHOMIII_BACKEND_PORT: String(backendPort), CHOMIII_FRONTEND_PORT: String(frontendPort) }
const app = spawn(process.execPath, [path.join(root, 'tools/launcher/start.mjs')], {
  cwd: root, env, stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
})
let logs = ''
for (const stream of [app.stdout, app.stderr]) stream.on('data', chunk => { logs += chunk })
async function run(cwd, args, extraEnv) {
  await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, args, { cwd, env: { ...process.env, ...extraEnv }, stdio: 'inherit' })
    child.once('error', reject)
    child.once('exit', code => code === 0 ? resolve() : reject(new Error(`Benchmark exited ${code}`)))
  })
}
try {
  for (let i = 0; i < 180; i++) {
    if (app.exitCode !== null) throw new Error('Local app stopped before the benchmark started.')
    try { if ((await fetch(`${origin}/api/health`, { signal: AbortSignal.timeout(2000) })).ok) break }
    catch { /* Starting. */ }
    if (i === 179) throw new Error('Local app startup timed out.')
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  await run(root, [path.join(root, 'tools/bench/ttft.mjs')], {
    CHOMIII_BENCH_API: `${origin}/api`, CHOMIII_BENCH_OUTPUT: path.join(root, 'docs/perf/after.json'),
    CHOMIII_BENCH_SAMPLES: process.env.CHOMIII_BENCH_SAMPLES ?? '5',
  })
  await run(path.join(root, 'frontend'), [path.join(root, 'frontend/node_modules/@playwright/test/cli.js'),
    'test', '-c', 'playwright.benchmark.config.ts'], {
    CHOMIII_BENCH_UI: origin, CHOMIII_RUN_BROWSER_BENCH: 'true',
    CHOMIII_BENCH_OUTPUT: path.join(root, 'docs/perf/browser-after.json'),
    CHOMIII_BENCH_SAMPLES: process.env.CHOMIII_BENCH_SAMPLES ?? '5',
  })
  const timings = logs.split(/\r?\n/).filter(line => line.includes('chat_latency ') || line.includes('gemini_timing '))
  const sanitized = timings.map(line => line.slice(line.indexOf('chat_latency ') >= 0
    ? line.indexOf('chat_latency ') : line.indexOf('gemini_timing ')))
  await writeFile(path.join(root, 'docs/perf/stages-after.txt'), sanitized.join('\n') + '\n')
  console.log(`Recorded ${sanitized.length} backend timing events.`)
} catch (error) {
  console.error('Benchmark failed:', error.message)
  process.exitCode = 1
} finally {
  if (app.exitCode === null) {
    const exited = new Promise(resolve => app.once('exit', resolve))
    app.send('stop')
    await exited
  }
}
