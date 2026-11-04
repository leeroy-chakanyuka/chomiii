import assert from 'node:assert/strict'
import { mkdtemp } from 'node:fs/promises'
import { createServer } from 'node:net'
import { spawn } from 'node:child_process'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const launcher = path.join(path.dirname(fileURLToPath(import.meta.url)), 'start.mjs')
const data = await mkdtemp(path.join(tmpdir(), 'chomiii-launcher-test-'))
async function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => { const port = server.address().port; server.close(() => resolve(port)) })
  })
}
const apiPort = await freePort()
const uiPort = await freePort()
const base = `http://127.0.0.1:${uiPort}`
let processHandle
let output = ''
async function start() {
  processHandle = spawn(process.execPath, [launcher], {
    env: { ...process.env, CHOMIII_DATA_DIR: data, CHOMIII_NO_BROWSER: '1',
      CHOMIII_BACKEND_PORT: String(apiPort), CHOMIII_FRONTEND_PORT: String(uiPort) },
    stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
  })
  for (const stream of [processHandle.stdout, processHandle.stderr]) stream.on('data', chunk => { output += chunk })
  for (let i = 0; i < 180; i++) {
    if (processHandle.exitCode !== null) throw new Error('Launcher exited before becoming ready')
    try { if ((await fetch(`${base}/api/health`, { signal: AbortSignal.timeout(2000) })).ok) return }
    catch { /* Starting. */ }
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  throw new Error('Launcher did not become ready')
}
async function stop() {
  if (!processHandle || processHandle.exitCode !== null) return
  const exited = new Promise(resolve => processHandle.once('exit', resolve))
  processHandle.send('stop')
  await exited
}

try {
  await start()
  const session = await fetch(`${base}/api/conversations`)
  const cookie = session.headers.get('set-cookie')?.split(';')[0]
  assert.ok(cookie, 'The local app must issue a session cookie')
  const created = await fetch(`${base}/api/conversations`, {
    method: 'POST', headers: { cookie, origin: base },
  })
  assert.equal(created.status, 201)
  const conversation = await created.json()
  await stop()
  await start()
  const reopened = await fetch(`${base}/api/conversations/${conversation.id}`, { headers: { cookie } })
  assert.equal(reopened.status, 200)
  assert.equal((await reopened.json()).id, conversation.id)
  console.log('PASS: launcher starts frontend and backend, and reopens a conversation after restart.')
} catch (error) {
  console.error(output)
  console.error(error)
  process.exitCode = 1
} finally {
  await stop()
}
