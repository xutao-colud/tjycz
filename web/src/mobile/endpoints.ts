export function validateServiceBase(value: string, development = false) {
  if(!value.trim())throw new Error('请在 .env.app.local 配置 VITE_API_BASE_URL（机构 HTTPS 接口地址）。')
  const url = new URL(value)
  if (url.username || url.password || url.search || url.hash || !/^https?:$/.test(url.protocol)) throw new Error('服务地址必须是无凭据、查询参数的 HTTP(S) 地址。')
  if (!development && url.protocol !== 'https:') throw new Error('正式 App 必须配置 HTTPS 服务地址。')
  return url.href.replace(/\/$/, '')
}

export function serviceUrl(base: string, path: string) {
  if (!path.startsWith('/') || path.startsWith('//')) throw new Error('接口路径格式错误。')
  return base.replace(/\/$/, '') + path
}

export function attachmentUrl(base: string, path: string) {
  if (path.startsWith('/api/files/')) return serviceUrl(base, path.slice(4))
  // Only open files issued by the configured service, including refreshed access tickets.
  const expected = new URL(base, 'https://localhost')
  const url = new URL(path, expected)
  if (url.origin !== expected.origin || !url.pathname.startsWith(expected.pathname.replace(/\/$/, '') + '/files/')) throw new Error('附件地址不属于当前机构服务。')
  return path
}
