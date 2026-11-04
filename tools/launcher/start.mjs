import EmbeddedPostgres from 'embedded-postgres'
import { randomBytes } from 'node:crypto'
import { spawn, execFile } from 'node:child_process'
import { access, mkdir, readFile, writeFile } from 'node:fs/promises'
import { createServer } from 'node:net'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const backend = path.join(root, 'backend')
const frontend = path.join(root, 'frontend')
const localData = process.env.CHOMIII_DATA_DIR ?? path.join(process.env.LOCALAPPDATA ?? process.env.HOME, 'chomiii')
const data = path.join(localData, 'postgres')
const credentialsPath = path.join(localData, 'database.json')
const apiPort = Number(process.env.CHOMIII_BACKEND_PORT ?? '8080')
const uiPort = Number(process.env.CHOMIII_FRONTEND_PORT ?? '5173')
const origin = `http://127.0.0.1:${uiPort}`

async function available(port) {
  return new Promise((resolve) => {
    const socket = createServer()
    socket.once('error', () => resolve(false))
    socket.listen(port, '127.0.0.1', () => socket.close(() => resolve(true)))
  })
}

async function freePort() {
  return new Promise((resolve, reject) => {
    const socket = createServer()
    socket.once('error', reject)
    socket.listen(0, '127.0.0.1', () => {
      const port = socket.address().port
      socket.close(() => resolve(port))
    })
  })
}

async function credentials() {
  await mkdir(localData, { recursive: true })
  try {
    const saved = JSON.parse(await readFile(credentialsPath, 'utf8'))
    if (typeof saved.password !== 'string' || !/^[a-f0-9]{48}$/.test(saved.password)) {
      throw new Error('Local database credentials are invalid. Check ' + credentialsPath)
    }
    return saved.password
  } catch (error) {
    if (error.code !== 'ENOENT') throw error
    if (await isInitialized()) throw new Error('The local database exists but its credentials file is missing. Do not remove the database.')
    const password = randomBytes(24).toString('hex')
    await writeFile(credentialsPath, JSON.stringify({ password }), { flag: 'wx', mode: 0o600 })
    return password
  }
}

async function isInitialized() {
  try { await access(path.join(data, 'PG_VERSION')); return true }
  catch (error) { if (error.code === 'ENOENT') return false; throw error }
}

async function waitFor(url, child, name) {
  for (let attempt = 0; attempt < 120; attempt++) {
    if (child.startError) throw new Error(`${name} could not start: ${child.startError.message}`)
    if (child.exitCode !== null) throw new Error(`${name} stopped with exit code ${child.exitCode}.`)
    try {
      if ((await fetch(url, { signal: AbortSignal.timeout(2000) })).ok) return
    } catch { /* The service is starting. */ }
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  throw new Error(`${name} did not become ready.`)
}

if (!(await available(apiPort)) || !(await available(uiPort))) {
  throw new Error(`Port ${apiPort} or ${uiPort} is already in use. Stop the existing service before starting Chomiii.`)
}
const dbPort = await freePort()
const password = await credentials()
const database = new EmbeddedPostgres({ databaseDir: data, user: 'chomiii', password, port: dbPort,
  persistent: true, autoRehydrateSymlinks: true, postgresFlags: ['-c', 'listen_addresses=127.0.0.1'],
  onLog: () => {}, onError: () => console.error('Local PostgreSQL reported an error.'),
})
let api
let ui
let stopping = false

async function stop() {
  if (stopping) return
  stopping = true
  if (ui && ui.exitCode === null) ui.kill()
  if (api && api.exitCode === null) {
    if (process.platform === 'win32' && api.pid) {
      await new Promise(resolve => execFile('taskkill', ['/pid', String(api.pid), '/t', '/f'], resolve))
    } else api.kill()
  }
  await database.stop().catch(() => {})
}

process.on('SIGINT', () => { void stop().then(() => process.exit(0)) })
process.on('SIGTERM', () => { void stop().then(() => process.exit(0)) })
process.on('message', message => { if (message === 'stop') void stop().then(() => process.exit(0)) })

try {
  if (!(await isInitialized())) {
    console.log('Initializing the local PostgreSQL database. Your chats will remain on this device.')
    await database.initialise()
  }
  await database.start()
  console.log('PostgreSQL is ready.')
  const env = { ...process.env, SPRING_PROFILES_ACTIVE: 'local', SERVER_PORT: String(apiPort),
    CHOMIII_DB_URL: `jdbc:postgresql://127.0.0.1:${dbPort}/postgres`,
    CHOMIII_DB_USER: 'chomiii', CHOMIII_DB_PASSWORD: password, CHOMIII_PUBLIC_ORIGIN: origin }
  delete env.GEMINI_API_KEY
  delete env.GOOGLE_API_KEY
  const windows = process.platform === 'win32'
  api = windows
    ? spawn('cmd.exe', ['/d', '/c', 'mvnw.cmd', 'spring-boot:run'], { cwd: backend, env, stdio: 'inherit' })
    : spawn('./mvnw', ['spring-boot:run'], { cwd: backend, env, stdio: 'inherit' })
  api.on('error', error => { api.startError = error })
  await waitFor(`http://127.0.0.1:${apiPort}/api/health`, api, 'Backend')
  ui = spawn(process.execPath, [path.join(frontend, 'node_modules', 'vite', 'bin', 'vite.js'),
    '--host', '127.0.0.1', '--port', String(uiPort), '--strictPort'],
  { cwd: frontend, env: { ...process.env, BACKEND_URL: `http://127.0.0.1:${apiPort}` }, stdio: 'inherit' })
  ui.on('error', error => { ui.startError = error })
  await waitFor(origin, ui, 'Frontend')
  console.log(`\nChomiii is ready at ${origin}/`)
  if (windows && process.env.CHOMIII_NO_BROWSER !== '1') {
    execFile('rundll32.exe', ['url.dll,FileProtocolHandler', `${origin}/`])
  }
  setInterval(() => {
    if (!stopping && (api.exitCode !== null || ui.exitCode !== null)) {
      console.error('A Chomiii service stopped. Shutting down the launcher.')
      void stop().then(() => process.exit(1))
    }
  }, 3000)
} catch (error) {
  console.error('Chomiii could not start:', error.message)
  await stop()
  process.exitCode = 1
}
