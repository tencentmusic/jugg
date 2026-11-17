<script setup>
import { onMounted, ref } from 'vue'
import { resolveRedirect } from '../../scripts/redirect-rules.mjs'

const redirecting = ref(false)
const targetUrl = ref('')

onMounted(() => {
  const currentPath = window.location.pathname
  const target = resolveRedirect(currentPath, '/')
  if (target) {
    redirecting.value = true
    targetUrl.value = target
    window.location.replace(target + window.location.search + window.location.hash)
  }
})
</script>

<template>
  <div class="custom-not-found" style="text-align: center; padding: 80px 24px; min-height: 50vh;">
    <div v-if="redirecting">
      <h1 style="font-size: 2rem; font-weight: bold; margin-bottom: 16px;">Redirecting / 页面正在跳转</h1>
      <p style="color: var(--vp-c-text-2); margin-bottom: 24px;">
        This page has moved. Redirecting to:
        <br>
        <a :href="targetUrl" style="color: var(--vp-c-brand-1); font-weight: 500; word-break: break-all;">{{ targetUrl }}</a>
      </p>
    </div>
    <div v-else>
      <h1 style="font-size: 5rem; font-weight: 800; margin-bottom: 16px; color: var(--vp-c-brand-1);">404</h1>
      <p style="font-size: 1.25rem; font-weight: 500; margin-bottom: 8px;">PAGE NOT FOUND</p>
      <p style="color: var(--vp-c-text-2); margin-bottom: 32px;">The page you are looking for does not exist or has been moved.</p>
      <a href="/" style="display: inline-block; padding: 10px 20px; border-radius: 8px; background-color: var(--vp-c-brand-1); color: white; text-decoration: none; font-weight: 500;">
        Go to Home
      </a>
    </div>
  </div>
</template>
