import { readdir, readFile } from 'node:fs/promises'
import { join } from 'node:path'

const assetsDir = new URL('../.vitepress/dist/assets/', import.meta.url)
const chineseHomepage = new URL('../.vitepress/dist/zh/index.html', import.meta.url)
const homepagePaths = ['index.md', 'zh/index.md']
const wikiBase = process.env.JUGG_WIKI_BASE || '/'
const assetNames = await readdir(assetsDir)
const pageAssets = await Promise.all(
  assetNames
    .filter((name) => name.endsWith('.js'))
    .map(async (name) => ({
      name,
      content: await readFile(join(assetsDir.pathname, name), 'utf8')
    }))
)
const homepageHtml = await readFile(chineseHomepage, 'utf8')
const homepageStyleName = homepageHtml
  .split(`href="${wikiBase}assets/`)
  .at(1)
  ?.match(/^(style\.[^"]+\.css)"/)?.[1]

if (!homepageStyleName) {
  throw new Error('Missing built homepage styles')
}

const homepageStyle = await readFile(join(assetsDir.pathname, homepageStyleName), 'utf8')

if (homepageStyle.includes('.jugg-run-scene:after')) {
  throw new Error('Run scene offset border can overlap its content')
}

for (const homepagePath of homepagePaths) {
  const pageAsset = pageAssets.find(({ name, content }) =>
    !name.endsWith('.lean.js') && content.includes(`"relativePath":"${homepagePath}"`)
  )

  if (!pageAsset) {
    throw new Error(`Missing built homepage asset for ${homepagePath}`)
  }

  if (pageAsset.content.includes('<pre><code>&lt;div class=&quot;jugg-')) {
    throw new Error(`Homepage HTML rendered as code in ${homepagePath}`)
  }

  if (!pageAsset.content.includes('class:"jugg-run-scene"')) {
    throw new Error(`Missing rendered Run scene in ${homepagePath}`)
  }

  if (!pageAsset.content.includes('class:"jugg-video-shell"')) {
    throw new Error(`Missing rendered demo video in ${homepagePath}`)
  }

  if (!pageAsset.content.includes('class:"jugg-compat"')) {
    throw new Error(`Missing rendered compatibility rail in ${homepagePath}`)
  }

  if (!pageAsset.content.includes('class:"jugg-stack jugg-band"')) {
    throw new Error(`Missing rendered technology stack section in ${homepagePath}`)
  }

  if (pageAsset.content.includes('class:"jugg-safety jugg-band"')) {
    throw new Error(`Removed compile and deploy section still renders in ${homepagePath}`)
  }
}

console.log('Homepage render check passed')
