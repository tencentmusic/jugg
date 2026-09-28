import { execFileSync } from 'node:child_process'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { setTimeout } from 'node:timers/promises'

const siteRoot = 'https://tencentmusic.github.io/jugg/'
const repoRoot = fileURLToPath(new URL('../../..', import.meta.url))
const publicDir = new URL('../public/', import.meta.url)
const keyFiles = readdirSync(publicDir).filter((name) => /^[a-f0-9]{32}\.txt$/.test(name))

if (keyFiles.length !== 1) throw new Error('Expected exactly one IndexNow key file')

const key = keyFiles[0].slice(0, -4)
if (readFileSync(new URL(keyFiles[0], publicDir), 'utf8').trim() !== key) {
  throw new Error('IndexNow key file content does not match its filename')
}
const keyLocation = new URL(keyFiles[0], siteRoot).href

function changedPaths(before) {
  return execFileSync('git', [
    'diff', '--no-renames', '--name-only', '-z', before, 'HEAD', '--', 'docs/wiki'
  ], { cwd: repoRoot, encoding: 'utf8' }).split('\0').filter(Boolean)
}

function pageUrl(path) {
  if (!path.startsWith('docs/wiki/') || !path.endsWith('.md')) return null
  const relative = path.slice('docs/wiki/'.length)
  if (relative.startsWith('dev/') || relative.startsWith('zh/dev/')) return null
  const page = relative.slice(0, -3).replace(/(^|\/)index$/, '$1')
  return new URL(page, siteRoot).href
}

function allPageUrls() {
  return execFileSync('git', ['ls-files', '-z', '--', 'docs/wiki'], {
    cwd: repoRoot, encoding: 'utf8'
  }).split('\0').map(pageUrl).filter(Boolean)
}

function urlsToSubmit() {
  const before = process.env.INDEXNOW_BEFORE
  const all = process.env.INDEXNOW_ALL === 'true' || /^0+$/.test(before || '')
  if (!all && !before) throw new Error('INDEXNOW_BEFORE is required for a push')

  const paths = all ? [] : changedPaths(before)
  const keyChanged = paths.includes(`docs/wiki/public/${keyFiles[0]}`)
  const urls = all || keyChanged ? allPageUrls() : paths.map(pageUrl).filter(Boolean)
  const unique = [...new Set(urls)].sort()
  if (unique.length > 10000) throw new Error('IndexNow accepts at most 10,000 URLs per request')
  if (unique.some((url) => !url.startsWith(siteRoot))) {
    throw new Error('IndexNow URL is outside the Wiki path')
  }
  return unique
}

async function verifyKey() {
  for (let attempt = 0; attempt < 2; attempt++) {
    const response = await fetch(keyLocation, { signal: AbortSignal.timeout(15000), cache: 'no-store' })
    if (response.ok && (await response.text()).trim() === key) return
    if (attempt === 0) await setTimeout(15000)
  }
  throw new Error(`IndexNow key is not available at ${keyLocation}`)
}

function postUrls(urlList) {
  return fetch('https://api.indexnow.org/indexnow', {
    method: 'POST',
    headers: { 'content-type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ host: 'tencentmusic.github.io', key, keyLocation, urlList }),
    signal: AbortSignal.timeout(15000)
  })
}

async function main() {
  const urlList = urlsToSubmit()
  console.log(`IndexNow URLs: ${urlList.length}`)
  if (process.argv.includes('--dry-run')) {
    for (const url of urlList) console.log(url)
    return
  }
  if (urlList.length === 0) return

  await verifyKey()
  let response = await postUrls(urlList)
  if (response.status === 403) {
    const body = await response.text()
    let errorCode
    try { errorCode = JSON.parse(body).errorCode } catch { /* Non-JSON errors fail below. */ }
    if (errorCode !== 'SiteVerificationNotCompleted') {
      throw new Error(`IndexNow returned HTTP 403: ${body.slice(0, 500)}`)
    }
    console.log('IndexNow site verification is pending; retrying once in 60 seconds')
    await setTimeout(60_000)
    response = await postUrls(urlList)
  }
  if (response.status !== 200 && response.status !== 202) {
    throw new Error(`IndexNow returned HTTP ${response.status}: ${(await response.text()).slice(0, 500)}`)
  }
  console.log(`IndexNow accepted ${urlList.length} URLs (HTTP ${response.status})`)
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
