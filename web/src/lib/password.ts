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
  const subtle = globalThis.crypto?.subtle
  if (!subtle) throw new Error('当前环境无法使用安全密码加密，请通过 HTTPS 或 localhost 访问。')
  if (typeof username !== 'string' || typeof password !== 'string') throw new Error('请填写登录账号和密码。')
  const normalizedUsername = username.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '')
  const challenge = await api<PasswordChallenge>(`/auth/challenge?purpose=${purpose}`)
  if (challenge.algorithm !== 'RSA-OAEP-256') throw new Error('服务端返回的密码加密算法不受支持。')
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
    { name: 'RSA-OAEP', label: encoder.encode(JSON.stringify(['wanfu-password-v1', purpose, challenge.challengeId, normalizedUsername])) },
    key,
    encoder.encode(password),
  )
  if (version !== contextVersion.value) throw contextChanged()
  return {
    ...fields,
    username: normalizedUsername,
    challengeId: challenge.challengeId,
    encryptedPassword: btoa(String.fromCharCode(...new Uint8Array(ciphertext))),
  }
}
