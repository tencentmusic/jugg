/**
 * Generates static HTML redirect files in .vitepress/dist to resolve legacy URLs with HTTP 200.
 */

import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { STATIC_REDIRECTS } from './redirect-rules.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const rootDir = path.resolve(__dirname, '..')
const distDir = path.resolve(rootDir, '.vitepress/dist')
const wikiBase = process.env.JUGG_WIKI_BASE || '/'
const normalizedBase = wikiBase.endsWith('/') ? wikiBase : `${wikiBase}/`
const siteHostname = 'https://tencentmusic.github.io'

function ensureDir(filePath) {
  const dir = path.dirname(filePath)
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true })
  }
}

function renderRedirectHtml(targetUrl, canonicalUrl) {
  return `<!DOCTYPE html>
<!-- jugg-wiki-redirect -->
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Redirecting...</title>
  <meta http-equiv="refresh" content="0; url=${targetUrl}">
  <link rel="canonical" href="${canonicalUrl}">
  <script>window.location.replace("${targetUrl}" + window.location.search + window.location.hash);</script>
</head>
<body>
  <p>Redirecting to <a href="${targetUrl}">${targetUrl}</a>...</p>
</body>
</html>
`
}

function isRedirectFile(filePath) {
  try {
    const head = fs.readFileSync(filePath, 'utf-8').slice(0, 200)
    return head.includes('<!-- jugg-wiki-redirect -->') || head.includes('Redirecting...')
  } catch {
    return false
  }
}

export function generateRedirects() {
  if (!fs.existsSync(distDir)) {
    console.warn(`[generate-redirects] Dist directory does not exist: ${distDir}`)
    return
  }

  let generatedCount = 0

  for (const [fromPath, toPath] of Object.entries(STATIC_REDIRECTS)) {
    const isExternal = toPath.startsWith('http://') || toPath.startsWith('https://')
    const targetUrl = isExternal
      ? toPath
      : `${normalizedBase}${toPath.replace(/^\/+/, '')}`

    const canonicalUrl = isExternal
      ? toPath
      : `${siteHostname}/jugg/${toPath.replace(/^\/+/, '')}`

    const cleanFrom = fromPath.replace(/^\/+/, '')

    // For cleanUrls, GitHub Pages serves <cleanFrom>.html or <cleanFrom>/index.html
    const fileVariants = []

    if (cleanFrom.endsWith('/')) {
      fileVariants.push(path.join(distDir, cleanFrom, 'index.html'))
    } else {
      fileVariants.push(path.join(distDir, `${cleanFrom}.html`))
      fileVariants.push(path.join(distDir, cleanFrom, 'index.html'))
    }

    const htmlContent = renderRedirectHtml(targetUrl, canonicalUrl)

    for (const filePath of fileVariants) {
      // Do not overwrite existing regular pages
      if (fs.existsSync(filePath) && !isRedirectFile(filePath)) {
        continue
      }
      ensureDir(filePath)
      fs.writeFileSync(filePath, htmlContent, 'utf-8')
      generatedCount++
    }
  }

  console.log(`[generate-redirects] Successfully generated ${generatedCount} redirect files in dist.`)
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  generateRedirects()
}
