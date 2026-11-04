import { api, contextVersion, contextChanged } from './api'

type PasswordPurpose = 'LOGIN' | 'CREATE_USER'
interface PasswordChallenge {
  challengeId: string
  publicKey: string
  algorithm: 'RSA-OAEP-256'
  expiresAt: string
}

export async function encryptPassword<T extends Record<string, unknown>>(purpose: PasswordPurpose, body: T) {
  const version = contextVersion.value
  const { password, username, ...fields } = body
  const crypto = globalThis.crypto
  const subtle = crypto?.subtle
  if (!subtle && !crypto?.getRandomValues) throw new Error('当前浏览器无法提供安全随机数，请升级浏览器后重试。')
  if (typeof username !== 'string' || typeof password !== 'string') throw new Error('请填写登录账号和密码。')
  const normalizedUsername = username.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '')
  const challenge = await api<PasswordChallenge>(`/auth/challenge?purpose=${purpose}`)
  if (challenge.algorithm !== 'RSA-OAEP-256') throw new Error('服务端返回的密码加密算法不受支持。')
  const label = JSON.stringify(['wanfu-password-v1', purpose, challenge.challengeId, normalizedUsername])
  let encryptedPassword: string
  if (subtle) {
    const key = await subtle.importKey(
      'spki',
      Uint8Array.from(atob(challenge.publicKey), char => char.charCodeAt(0)),
      { name: 'RSA-OAEP', hash: 'SHA-256' },
      false,
      ['encrypt'],
    )
    if ((key.algorithm as RsaHashedKeyAlgorithm).modulusLength !== 2048) throw new Error('服务端返回的密码加密密钥不受支持。')
    const encoder = new TextEncoder()
    const ciphertext = await subtle.encrypt(
      { name: 'RSA-OAEP', label: encoder.encode(label) }, key, encoder.encode(password),
    )
    encryptedPassword = btoa(String.fromCharCode(...new Uint8Array(ciphertext)))
  } else {
    // HTTP 页面没有 SubtleCrypto，沿用小程序的 OAEP 算法和平台安全随机源。
    const { default: forge } = await import('node-forge')
    const key = forge.pki.publicKeyFromAsn1(forge.asn1.fromDer(forge.util.decode64(challenge.publicKey)))
    if (key.n.bitLength() !== 2048) throw new Error('服务端返回的密码加密密钥不受支持。')
    const seed = String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32)))
    encryptedPassword = forge.util.encode64(key.encrypt(forge.util.encodeUtf8(password), 'RSA-OAEP', {
      md: forge.md.sha256.create(), mgf1: { md: forge.md.sha256.create() }, label: forge.util.encodeUtf8(label), seed,
    }))
  }
  if (version !== contextVersion.value) throw contextChanged()
  return {
    ...fields,
    username: normalizedUsername,
    challengeId: challenge.challengeId,
    encryptedPassword,
  }
}
