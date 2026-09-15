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
  }
} satisfies Theme
