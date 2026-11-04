# Password encryption dependency

`forge.js` is a CommonJS browser bundle of node-forge **1.4.1**, distributed under the BSD-3-Clause option in `forge-LICENSE.txt`.

Upstream: https://github.com/digitalbazaar/forge
Registry: https://registry.npmjs.org/node-forge/-/node-forge-1.4.1.tgz
Registry SHA-512 integrity: `sha512-w5/MfWaeMrpR/5ba6fZyAUTLSuE6aYz7H/3cYrqwZEmf5eQS3IaaLgJt4MBv7HglGFWmV22K8h4U18RJQmw/ow==`

The entry imports `lib/forge`, `lib/rsa`, and `lib/sha256`. It is bundled using esbuild for browser/CommonJS, target ES2017. Node/global detection is disabled. Every import of `./random` is replaced with a fail-closed adapter whose `getBytes` throws. Native Node `crypto` is unavailable in this bundle.

`utils/password.js` explicitly supplies a 32-byte OAEP seed obtained from `wx.getRandomValues`. No fallback to Math.random or a weak entropy source is allowed. The dependency is used only for SPKI decoding and RSA-OAEP SHA-256/MGF1-SHA256 public-key encryption; private keys and decryption stay on the server.

Run `node scripts/check-password.js` to check the bundle in an environment without Node or browser globals, its interoperability with the Node crypto implementation, randomized ciphertext, and failure of login when the platform secure random API is unavailable.
