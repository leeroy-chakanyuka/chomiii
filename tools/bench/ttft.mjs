import { performance } from 'node:perf_hooks'
import { writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'

const key = process.env.GEMINI_API_KEY
const model = process.env.CHOMIII_BENCH_MODEL ?? 'gemini-3.5-flash-lite'
const api = (process.env.CHOMIII_BENCH_API ?? 'http://127.0.0.1:5173/api').replace(/\/$/, '')
const origin = process.env.CHOMIII_BENCH_ORIGIN ?? new URL(api).origin
const count = Number(process.env.CHOMIII_BENCH_SAMPLES ?? '10')
const prompt = 'What is 2 + 2? Reply with the single digit.'
const instruction = process.env.CHOMIII_BENCH_INSTRUCTION ?? 'You are Chomiii, also called Chomi, a helpful general-purpose assistant. Be warm, clear, and honest. Admit uncertainty. You have no tools, live browsing, or long-term memory.'

if (!key || !/^[a-z0-9.-]+$/.test(model) || !Number.isInteger(count) || count < 2 || count > 50) {
  throw new Error('Set GEMINI_API_KEY and a valid model; CHOMIII_BENCH_SAMPLES must be between 2 and 50.')
}

async function events(response, onEvent) {
  if (!response.body) throw new Error('EMPTY_STREAM')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let pending = ''
  try {
    while (true) {
      const { value, done } = await reader.read()
      pending += done ? decoder.decode() : decoder.decode(value, { stream: true })
      pending = pending.replace(/\r\n/g, '\n')
      let boundary = pending.indexOf('\n\n')
      while (boundary !== -1) {
        const frame = pending.slice(0, boundary)
        pending = pending.slice(boundary + 2)
        const raw = frame.split('\n').filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart()).join('\n')
        if (raw) onEvent(raw, frame)
        boundary = pending.indexOf('\n\n')
      }
      if (done) return
      if (pending.length > 65536) throw new Error('OVERSIZED_STREAM')
    }
  } finally {
    reader.releaseLock()
  }
}

async function request(url, options) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(90000) })
  if (!response.ok) throw new Error(`HTTP_${response.status}`)
  return response
}

async function session() {
  const response = await request(`${api}/conversations`)
  const cookie = response.headers.get('set-cookie')?.split(';')[0]
  if (!cookie) throw new Error('NO_SESSION')
  return cookie
}

async function app(cookie) {
  const createdAt = performance.now()
  const created = await request(`${api}/conversations`, { method: 'POST', headers: { Cookie: cookie, Origin: origin } })
  const { id } = await created.json()
  if (typeof id !== 'string') throw new Error('NO_CONVERSATION')
  const createMs = performance.now() - createdAt
  try {
    const start = performance.now()
    const response = await request(`${api}/conversations/${id}/messages/stream`, {
      method: 'POST', headers: { Cookie: cookie, Origin: origin, 'Content-Type': 'application/json',
        'X-Gemini-Api-Key': key },
      body: JSON.stringify({ requestId: randomUUID(), provider: 'gemini', model, content: prompt }),
    })
    let firstMs = null
    let answer = ''
    let complete = false
    await events(response, (raw, frame) => {
      if (frame.includes('event:error')) throw new Error('APP_STREAM_ERROR')
      if (frame.includes('event:complete')) complete = true
      if (frame.includes('event:delta')) {
        const value = JSON.parse(raw)
        if (typeof value.text !== 'string') throw new Error('BAD_DELTA')
        if (value.text && firstMs === null) firstMs = performance.now() - start
        answer += value.text
      }
    })
    if (!complete || firstMs === null || !answer.includes('4')) throw new Error('BAD_APP_ANSWER')
    return { createMs: +createMs.toFixed(1), ttftMs: +firstMs.toFixed(1), completeMs: +(performance.now() - start).toFixed(1) }
  } finally {
    await request(`${api}/conversations/${id}`, { method: 'DELETE', headers: { Cookie: cookie, Origin: origin } })
  }
}

async function direct() {
  const start = performance.now()
  const response = await request(`https://generativelanguage.googleapis.com/v1beta/models/${model}:streamGenerateContent?alt=sse`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({ systemInstruction: { parts: [{ text: instruction }] },
      contents: [{ role: 'user', parts: [{ text: prompt }] }] }),
  })
  let firstMs = null
  let answer = ''
  await events(response, raw => {
    const value = JSON.parse(raw)
    if (value.error) throw new Error('DIRECT_STREAM_ERROR')
    for (const part of value.candidates?.[0]?.content?.parts ?? []) {
      if (part.thought || typeof part.text !== 'string') continue
      if (part.text && firstMs === null) firstMs = performance.now() - start
      answer += part.text
    }
  })
  if (firstMs === null || !answer.includes('4')) throw new Error('BAD_DIRECT_ANSWER')
  return { ttftMs: +firstMs.toFixed(1), completeMs: +(performance.now() - start).toFixed(1) }
}

function summary(samples) {
  if (!samples.length) return { count: 0 }
  const result = { count: samples.length }
  for (const name of ['ttftMs', 'completeMs', 'createMs']) {
    const times = samples.map(sample => sample[name]).filter(value => typeof value === 'number').sort((a, b) => a - b)
    if (times.length) result[name] = { p50: times[Math.ceil(times.length * 0.50) - 1],
      p95: times[Math.ceil(times.length * 0.95) - 1], min: times[0], max: times.at(-1) }
  }
  return result
}

const cookie = await session()
const samples = { app: [], direct: [] }
const failures = []
for (let i = -1; i < count; i++) {
  for (const side of i % 2 === 0 ? ['app', 'direct'] : ['direct', 'app']) {
    try {
      const value = await (side === 'app' ? app(cookie) : direct())
      if (i >= 0) samples[side].push(value)
    } catch (error) {
      failures.push({ side, iteration: i, code: error instanceof Error && /^(HTTP_\d+|[A-Z_]+)$/.test(error.message)
        ? error.message : 'UNEXPECTED' })
    }
  }
  if (failures.length) break
}

const report = { model, prompt: '2+2 single-digit answer', generation: 'SDK default; fixed system instruction; no prior turns',
  targetSamplesPerSide: count, warmupsPerSide: 1, failures,
  app: { ...summary(samples.app), samples: samples.app },
  direct: { ...summary(samples.direct), samples: samples.direct } }
console.log(JSON.stringify(report, null, 2))
if (process.env.CHOMIII_BENCH_OUTPUT) {
  await writeFile(process.env.CHOMIII_BENCH_OUTPUT, JSON.stringify(report, null, 2) + '\n')
}
if (failures.length || samples.app.length !== count || samples.direct.length !== count) process.exitCode = 1
