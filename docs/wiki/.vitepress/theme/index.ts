import { inBrowser, type Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import './style.css'
import './home.css'

const GA_ID = 'G-GNEQK6VECM'

declare global {
  interface Window {
    gtag?: (...args: unknown[]) => void
  }
}

export default {
  extends: DefaultTheme,
  enhanceApp({ router }) {
    if (!inBrowser) return

    let isInitialRoute = true
    router.onAfterRouteChanged = (to) => {
      if (isInitialRoute) {
        isInitialRoute = false
        return
      }
      window.gtag?.('config', GA_ID, { page_path: to })
    }

    window.addEventListener('click', (event) => {
      const target = event.target as HTMLElement | null
      const copyBtn = target?.closest('button.copy')
      if (copyBtn) {
        const pre = copyBtn.parentElement?.querySelector('pre')
        const codeText = pre?.textContent || ''
        const preview = codeText.trim().slice(0, 80)
        window.gtag?.('event', 'code_copy', {
          page_path: window.location.pathname,
          snippet_preview: preview
        })
      }
    }, true)
  }
} satisfies Theme
