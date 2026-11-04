const { constants, createPublicKey, publicEncrypt } = require('node:crypto')
const { readFileSync } = require('node:fs')

try {
  const { challenge, purpose, username, password } = JSON.parse(readFileSync(0, 'utf8'))
  if (!['LOGIN', 'CREATE_USER'].includes(purpose) || typeof username !== 'string' || typeof password !== 'string') {
    throw new Error('Invalid password input')
  }
  if (challenge?.algorithm !== 'RSA-OAEP-256' || typeof challenge.challengeId !== 'string' || !challenge.challengeId || typeof challenge.publicKey !== 'string') {
    throw new Error('Invalid password challenge')
  }
  const key = createPublicKey({ key: Buffer.from(challenge.publicKey, 'base64'), format: 'der', type: 'spki' })
  if (key.asymmetricKeyType !== 'rsa' || key.asymmetricKeyDetails?.modulusLength !== 2048) {
    throw new Error('Invalid password key')
  }
  const encrypted = publicEncrypt({
    key,
    padding: constants.RSA_PKCS1_OAEP_PADDING,
    oaepHash: 'sha256',
    oaepLabel: Buffer.from(JSON.stringify(['wanfu-password-v1', purpose, challenge.challengeId, username.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '')]), 'utf8'),
  }, Buffer.from(password, 'utf8'))
  process.stdout.write(encrypted.toString('base64'))
} catch {
  process.stderr.write('Password encryption failed.\n')
  process.exitCode = 1
}
