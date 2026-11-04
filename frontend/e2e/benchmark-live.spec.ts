import { writeFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'

test('measure real-browser time to visible Gemini text', async ({ page }) => {
  const key = process.env.GEMINI_API_KEY
  test.skip(process.env.CHOMIII_RUN_BROWSER_BENCH !== 'true' || !key, 'Set the live benchmark flag and an authorized key.')
  if (!key) throw new Error('A Gemini API key is required.')
  const count = Number(process.env.CHOMIII_BENCH_SAMPLES ?? '5')
  if (!Number.isInteger(count) || count < 2 || count > 20) throw new Error('Invalid sample count.')

  await page.goto('/')
  await page.getByLabel('Gemini API key').fill(key)
  await page.getByRole('button', { name: 'Start chatting' }).click()
  const samples: { firstVisibleMs: number; completeMs: number }[] = []
  for (let i = -1; i < count; i++) {
    await page.getByRole('button', { name: /New chat/ }).click()
    await page.getByLabel('Message').fill('What is 2 + 2? Reply with the single digit.')
    await page.evaluate(`(() => {
      window.__chomiiiBench = { started: performance.now(), first: null };
      const observer = new MutationObserver(() => {
        const answer = document.querySelector('.message.assistant .markdown');
        if (answer && answer.textContent.trim()) {
          observer.disconnect();
          requestAnimationFrame(() => requestAnimationFrame(() => {
            window.__chomiiiBench.first = performance.now() - window.__chomiiiBench.started;
          }));
        }
      });
      observer.observe(document.querySelector('.conversation-view'), { childList: true, subtree: true, characterData: true });
    })()`)
    await page.getByRole('button', { name: 'Send' }).click()
    await page.waitForFunction('window.__chomiiiBench.first !== null', undefined, { timeout: 90_000 })
    const first: unknown = await page.evaluate('window.__chomiiiBench.first')
    await expect(page.locator('.message.assistant').last()).toContainText('4', { timeout: 90_000 })
    await expect(page.getByRole('button', { name: 'Send' })).toBeVisible({ timeout: 90_000 })
    const complete: unknown = await page.evaluate('performance.getEntriesByName("chomiii:response-complete").at(-1)?.duration')
    if (typeof first !== 'number' || typeof complete !== 'number') throw new Error('Browser timing was not recorded.')
    if (i >= 0) samples.push({ firstVisibleMs: +first.toFixed(1), completeMs: +complete.toFixed(1) })
  }
  const times = samples.map(item => item.firstVisibleMs).sort((a, b) => a - b)
  const report = { model: 'gemini-3.5-flash-lite', count, warmups: 1, failures: 0,
    p50Ms: times[Math.ceil(count * 0.5) - 1], p95Ms: times[Math.ceil(count * 0.95) - 1],
    minMs: times[0], maxMs: times.at(-1), samples }
  console.log(JSON.stringify(report, null, 2))
  if (process.env.CHOMIII_BENCH_OUTPUT) await writeFile(process.env.CHOMIII_BENCH_OUTPUT, JSON.stringify(report, null, 2) + '\n')
})
