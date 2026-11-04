import { performance } from 'node:perf_hooks'

const key = process.env.GEMINI_API_KEY
if (!key) throw new Error('GEMINI_API_KEY is required for the model-list network probe.')
const samples = []
for (let i = 0; i < 5; i++) {
  const started = performance.now()
  const response = await fetch('https://generativelanguage.googleapis.com/v1beta/models?pageSize=1', {
    headers: { 'x-goog-api-key': key }, signal: AbortSignal.timeout(30000),
  })
  const headersMs = +(performance.now() - started).toFixed(1)
  if (!response.ok) throw new Error(`Model-list probe returned HTTP ${response.status}`)
  const result = await response.json()
  if (!Array.isArray(result.models) || !result.models.length) throw new Error('Model-list probe returned no models')
  samples.push({ headersMs, totalMs: +(performance.now() - started).toFixed(1) })
}
const sorted = samples.map(sample => sample.totalMs).sort((a, b) => a - b)
console.log(JSON.stringify({ samples, p50Ms: sorted[2], p95Ms: sorted[4] }, null, 2))
