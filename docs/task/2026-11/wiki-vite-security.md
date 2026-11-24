# 修复 Wiki 构建依赖的 Vite 告警

[Dependabot 告警 #10 至 #14](https://github.com/tencentmusic/jugg/security/dependabot) 均指向 `docs/wiki/package-lock.json` 中的 Vite 6.4.3，GitHub 列出的最低修复版本为 6.4.4。

沿用 Wiki 的 npm override，将 Vite 更新到同一版本线的 6.4.6 并重新生成锁文件。VitePress 保持 1.6.4，其余依赖的解析版本保持原样。

## 验证

- 在 Node.js 24.22.3 下执行 `npm ci` 与 `npm ls vite --all`，VitePress 和 Vue 插件均解析到 Vite 6.4.6。
- 使用公共 npm registry 执行 `npm audit`，返回 0 个漏洞。
- 使用 `JUGG_WIKI_BASE=/jugg/ npm run check:homepage` 验证 GitHub Pages 路径下的生产构建、重定向生成和首页渲染，全部通过。
- 依赖审计和已有构建检查覆盖本次变更，无需新增测试。GitHub 告警状态在修复合入默认分支后核对。
