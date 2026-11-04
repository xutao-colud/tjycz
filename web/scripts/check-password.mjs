import assert from 'node:assert/strict'
import { generateKeyPairSync, privateDecrypt, webcrypto } from 'node:crypto'
import { createServer } from 'vite'

const originalCrypto = Object.getOwnPropertyDescriptor(globalThis, 'crypto')
const originalFetch = globalThis.fetch
const originalStorage = globalThis.localStorage
const pair = generateKeyPairSync('rsa', { modulusLength: 2048 })
const publicKey = pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64')
const challenge = { challengeId: 'fictional-challenge', publicKey, algorithm: 'RSA-OAEP-256', expiresAt: new Date(Date.now() + 60000).toISOString() }
let response = challenge
let requests = 0
globalThis.localStorage = { getItem: () => null }
globalThis.fetch = async url => {
  assert.match(url, /^\/api\/auth\/challenge\?purpose=(LOGIN|CREATE_USER)$/)
  requests++
  return new Response(JSON.stringify(response))
}
const setCrypto = value => Object.defineProperty(globalThis, 'crypto', { configurable: true, value })
const httpCrypto = { getRandomValues: bytes => webcrypto.getRandomValues(bytes) }
const server = await createServer({ server: { middlewareMode: true, hmr: false }, optimizeDeps: { noDiscovery: true, include: [] }, appType: 'custom' })
try {
  const { encryptPassword } = await server.ssrLoadModule('/src/lib/password.ts')
  const { contextVersion } = await server.ssrLoadModule('/src/lib/api.ts')
  for (const [name, crypto] of [['HTTP', httpCrypto], ['WebCrypto', webcrypto]]) {
    setCrypto(crypto)
    for (const purpose of ['LOGIN', 'CREATE_USER']) {
      const body = { username: '\u0000账号\u001f', password: '虚构密码 Test123!', role: 'TEACHER' }
      const result = await encryptPassword(purpose, body)
      assert.equal(result.username, '账号')
      assert.equal(result.role, 'TEACHER')
      assert.equal(result.challengeId, challenge.challengeId)
      assert.equal(Object.hasOwn(result, 'password'), false, '不得返回明文密码')
      assert.equal(Buffer.from(result.encryptedPassword, 'base64').length, 256)
      const label = Buffer.from(JSON.stringify(['wanfu-password-v1', purpose, challenge.challengeId, '账号']))
      const plaintext = privateDecrypt({ key: pair.privateKey, oaepHash: 'sha256', oaepLabel: label }, Buffer.from(result.encryptedPassword, 'base64')).toString('utf8')
      assert.equal(plaintext, body.password, `${name}/${purpose} 必须与后端 OAEP-SHA256/MGF1-SHA256 互通`)
      const retry = await encryptPassword(purpose, body)
      assert.notEqual(result.encryptedPassword, retry.encryptedPassword, '同密码每次密文不同')
      assert.throws(() => privateDecrypt({ key: pair.privateKey, oaepHash: 'sha256', oaepLabel: Buffer.from('wrong-context') }, Buffer.from(result.encryptedPassword, 'base64')))
    }
    response = { ...challenge, algorithm: 'RSA1_5' }
    await assert.rejects(encryptPassword('LOGIN', { username: 'fixture', password: 'fixture' }), /算法不受支持/)
    const smallKey = generateKeyPairSync('rsa', { modulusLength: 1024 })
    response = { ...challenge, publicKey: smallKey.publicKey.export({ type: 'spki', format: 'der' }).toString('base64') }
    await assert.rejects(encryptPassword('LOGIN', { username: 'fixture', password: 'fixture' }), /密钥不受支持/)
    response = challenge
    console.log(`PASS: ${name} login/create-user, UTF-8, OAEP label, randomized ciphertext, no plaintext, algorithm/key validation`)
  }
  setCrypto(undefined)
  const before = requests
  await assert.rejects(encryptPassword('LOGIN', { username: 'fixture', password: 'fixture' }), /安全随机数/)
  assert.equal(requests, before, '缺安全随机源时不能继续请求或明文降级')
  setCrypto({ getRandomValues() { throw new Error('fixture random unavailable') } })
  await assert.rejects(encryptPassword('LOGIN', { username: 'fixture', password: 'fixture' }), /random unavailable/)
  setCrypto({ getRandomValues(bytes) { contextVersion.value++; return webcrypto.getRandomValues(bytes) } })
  await assert.rejects(encryptPassword('LOGIN', { username: 'fixture', password: 'fixture' }), error => error.name === 'AbortError')
  setCrypto(httpCrypto)
  await assert.rejects(encryptPassword('LOGIN', { username: 'fixture', password: null }), /登录账号和密码/)
  console.log('PASS: missing/failed secure randomness, account/campus context cancellation and invalid input fail closed')
} finally {
  await server.close()
  Object.defineProperty(globalThis, 'crypto', originalCrypto)
  globalThis.fetch = originalFetch
  if (originalStorage === undefined) delete globalThis.localStorage
  else globalThis.localStorage = originalStorage
}
