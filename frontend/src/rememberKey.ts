const DATABASE = 'chomiii-device-key-v1'
const STORE = 'credentials'
const RECORD = 'gemini'

interface SavedKey {
  id: string
  model: string
  cipher: ArrayBuffer
  iv: Uint8Array
  key: CryptoKey
}

export interface RememberedKey {
  apiKey: string
  model: string
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1)
    request.onupgradeneeded = () => { request.result.createObjectStore(STORE, { keyPath: 'id' }) }
    request.onerror = () => reject(new Error('This browser could not open its saved credentials.'))
    request.onsuccess = () => resolve(request.result)
  })
}

function read(db: IDBDatabase): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const request = db.transaction(STORE, 'readonly').objectStore(STORE).get(RECORD)
    request.onerror = () => reject(new Error('This browser could not read its saved credential.'))
    request.onsuccess = () => resolve(request.result)
  })
}

function save(db: IDBDatabase, value: SavedKey): Promise<void> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE, 'readwrite')
    transaction.objectStore(STORE).put(value)
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(new Error('This browser could not save the key.'))
    transaction.onabort = () => reject(new Error('This browser could not save the key.'))
  })
}

function remove(db: IDBDatabase): Promise<void> {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE, 'readwrite')
    transaction.objectStore(STORE).delete(RECORD)
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(new Error('This browser could not remove the saved key.'))
    transaction.onabort = () => reject(new Error('This browser could not remove the saved key.'))
  })
}

export async function rememberKey(value: RememberedKey): Promise<void> {
  if (!globalThis.crypto?.subtle || typeof indexedDB === 'undefined') {
    throw new Error('Remembering a key requires a secure browser context.')
  }
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const cipher = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key,
    new TextEncoder().encode(value.apiKey))
  const db = await openDatabase()
  try { await save(db, { id: RECORD, model: value.model, cipher, iv, key }) }
  finally { db.close() }
}

export async function loadRememberedKey(): Promise<RememberedKey | null> {
  if (!globalThis.crypto?.subtle || typeof indexedDB === 'undefined') return null
  try {
    const db = await openDatabase()
    try {
      const entry = await read(db)
      if (!entry || typeof entry !== 'object' || !('key' in entry) || !('iv' in entry)
        || !('cipher' in entry) || !('model' in entry)
        || !(entry.key instanceof CryptoKey) || !(entry.iv instanceof Uint8Array)
        || !(entry.cipher instanceof ArrayBuffer) || typeof entry.model !== 'string') return null
      const iv = new Uint8Array(entry.iv.length)
      iv.set(entry.iv)
      const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, entry.key, entry.cipher)
      const apiKey = new TextDecoder().decode(plain)
      return apiKey ? { apiKey, model: entry.model } : null
    } finally { db.close() }
  } catch {
    return null
  }
}

export async function forgetRememberedKey(): Promise<void> {
  if (typeof indexedDB === 'undefined') return
  const db = await openDatabase()
  try { await remove(db) }
  finally { db.close() }
}
