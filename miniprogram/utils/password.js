const forge = require('./vendor/forge');

function randomSeed() {
  return new Promise((resolve, reject) => {
    if (typeof wx.getRandomValues !== 'function') {
      reject(new Error('当前微信版本不支持安全登录，请更新微信后重试'));
      return;
    }
    wx.getRandomValues({
      length: 32,
      success(result) {
        const bytes = new Uint8Array(result.randomValues);
        if (bytes.length !== 32) { reject(new Error('无法获取安全随机数，请重试')); return; }
        let seed = '';
        for (let index = 0; index < bytes.length; index++) seed += String.fromCharCode(bytes[index]);
        resolve(seed);
      },
      fail() { reject(new Error('无法获取安全随机数，请重试')); }
    });
  });
}

async function encryptPassword(challenge, username, password, purpose) {
  const name = String(username || '').replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '');
  if (!challenge || challenge.algorithm !== 'RSA-OAEP-256' || !challenge.challengeId || !challenge.publicKey || !Number.isFinite(Date.parse(challenge.expiresAt))) {
    throw new Error('服务端安全登录配置无效，请更新服务后重试');
  }
  if (Date.parse(challenge.expiresAt) <= Date.now()) throw new Error('安全登录请求已过期，请重新登录');
  if (!name || name.length > 80 || typeof password !== 'string' || !password) throw new Error('请输入有效账号和密码');
  const plaintext = forge.util.encodeUtf8(password);
  if (plaintext.length > 72) throw new Error('密码最多支持 72 个 UTF-8 字节');
  const seed = await randomSeed();
  try {
    const publicKey = forge.pki.publicKeyFromAsn1(forge.asn1.fromDer(forge.util.decode64(challenge.publicKey)));
    if (publicKey.n.bitLength() !== 2048 || publicKey.e.intValue() !== 65537) throw new Error('Unsupported RSA key');
    const label = forge.util.encodeUtf8(JSON.stringify(['wanfu-password-v1', purpose || 'LOGIN', challenge.challengeId, name]));
    const encrypted = publicKey.encrypt(plaintext, 'RSA-OAEP', { md: forge.md.sha256.create(), mgf1: { md: forge.md.sha256.create() }, label, seed });
    return { username: name, challengeId: challenge.challengeId, encryptedPassword: forge.util.encode64(encrypted) };
  } catch (error) {
    throw new Error('密码加密失败，请检查服务配置后重试');
  }
}
module.exports = { encryptPassword };
