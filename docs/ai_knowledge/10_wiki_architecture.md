# Wiki 架构与运行

> 最后核对：2026-08-04
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述 Jugg 用户 Wiki 的工程结构、开发运行、构建预览和发布边界。

本页不规定文章怎么写；文章写作见 `10_wiki_authoring.md`。

---

## 2. 核心文件索引

| 文件 | 作用 |
|---|---|
| `docs/wiki/package.json` | Wiki 开发、打包、产物预览的 npm scripts 入口；后续 npm 操作都在 `docs/wiki` 下执行。 |
| `docs/wiki/.vitepress/config.mts` | VitePress 站点配置，包含 base/nav/sidebar/search/dev-only 页面排除。 |
| `.agents/skills/wiki-writer/scripts/validate_wiki.py` | 检查中英文 Markdown 路径、nav/sidebar 路由顺序、相对链接、配置路由和构建产物。 |
| `.github/workflows/wiki-pages.yml` | `main` 分支 Wiki 变更触发 GitHub Pages 构建与发布。 |
| `.github/workflows/release.yml` | 版本 tag 触发正式 GitHub Release；仅 tag commit 已包含在 `main` 时构建，避免 develop tag 发布正式包。 |
| `.github/workflows/nightly.yml` | 每日或手工检查 `develop`；仅在其 HEAD 与 `canary-nightly` tag 不同时构建，并更新 Canary prerelease、插件包和 SHA-256。 |
| `.github/workflows/dev.yml` | 仅手工触发的构建验证；按 `<versionName>-dev.<日期>.<run number>` 构建被触发 ref，只上传 workflow artifact，不发 tag 或 release。 |
| `~/Documents/shell/publish_jugg_wiki.sh` | Wiki 后台发布脚本：打包 production 产物并同步到 `ali` / `yun` 后台 Wiki 根目录。 |
| `docs/wiki/dev/elements-demo.md` | 英文 dev-only 元素样板页，只用于开发环境视觉验收。 |
| `docs/wiki/zh/dev/elements-demo.md` | 中文 dev-only 元素样板页，只用于开发环境视觉验收。 |
| `docs/wiki/dev/assets/wiki-elements-demo.svg` | Demo 页使用的示例图片资源。 |

---

## 3. 站点结构

Wiki 使用 VitePress，源码根目录是 `docs/wiki`。

```text
docs/wiki/
  .vitepress/
    config.mts
  capabilities/
  concepts/
  guide/
  onboarding/
  reference/
  troubleshooting/
  zh/
    capabilities/
    concepts/
    guide/
    onboarding/
    reference/
    troubleshooting/
```

英文页面位于根路径，中文页面位于 `/zh/` 路径。中文是唯一内容基准；去掉 `zh/` 前缀后，中英文 Markdown 路径集合必须完全一致，nav/sidebar 的层级、顺序和目标页面也必须严格镜像。

---

## 4. dev-only 页面规则

需要视觉验收但不应该发布给用户的页面，放在 dev-only 路径：

```text
docs/wiki/dev/
docs/wiki/zh/dev/
```

必须同时满足：

1. 文件路径位于 dev-only 目录。
2. frontmatter 写 `visibility: dev`。
3. `docs/wiki/.vitepress/config.mts` 在 production build 中通过 `srcExclude` 排除路径。
4. dev 模式下才在 nav/sidebar 中挂入口。

当前 dev-only 识别逻辑：

```text
JUGG_WIKI_DEV=true 或 vitepress dev
  -> include dev pages

production build
  -> exclude dev/** and zh/dev/**
```

production build 后应确认 dist 中不存在 dev-only 页面标题。

---

## 5. 本地运行

Wiki 使用 VitePress，所有 npm 操作都以 `docs/wiki` 为工作目录。首次拉取或依赖变化后先安装依赖：

```bash
cd docs/wiki
npm ci
```

编辑 Wiki 时使用 dev server：

```bash
npm run dev
```

默认启动 VitePress dev server，保存 Markdown 或配置文件后会自动热更新页面。需要固定监听地址或端口时，通过 `--` 继续传 VitePress 参数：

```bash
npm run dev -- --host 127.0.0.1 --port 5173
```

dev 模式会自动包含 dev-only 页面，因为 `docs/wiki/.vitepress/config.mts` 中的 `isWikiDev` 会识别 `vitepress dev`。因此本地视觉验收可以直接访问：

```text
/dev/elements-demo
/zh/dev/elements-demo
```

---

## 6. Production 打包

发布前使用 production build：

```bash
npm run build
```

构建产物输出到：

```text
docs/wiki/.vitepress/dist/
```

production build 不应带 `JUGG_WIKI_DEV=true`。默认配置会通过 `srcExclude` 排除：

```text
dev/**
zh/dev/**
```

如果需要临时验证 dev-only 页面能否独立构建，可以单独执行：

```bash
JUGG_WIKI_DEV=true npm run build
```

该命令只用于开发验收，不作为发布产物。

---

## 7. 预览打包产物

`npm run dev` 预览的是源码开发态；发布前还需要预览已经生成的静态产物：

```bash
npm run preview
```

需要固定地址或端口时：

```bash
npm run preview -- --host 127.0.0.1 --port 4173
```

`npm run preview` 读取 `docs/wiki/.vitepress/dist/`，因此必须先执行 `npm run build`。

---

## 8. GitHub Pages 发布

GitHub Pages 使用项目站点路径：

```text
https://tencentmusic.github.io/jugg/
```

`.github/workflows/wiki-pages.yml` 在 `main` 分支的 `docs/wiki/**` 或 workflow 自身发生变化时执行，也支持手工触发。构建步骤在 `docs/wiki` 下运行 `npm ci` 和 `npm run check:homepage`，完成 production build 与首页渲染检查后，再将 `.vitepress/dist` 作为 Pages artifact 发布。

VitePress 的公开路径由 `JUGG_WIKI_BASE` 控制：

```text
GitHub Pages build -> JUGG_WIKI_BASE=/jugg/
默认本地或后台 build -> /
```

不要将 `base` 直接写死为 `/jugg/`，否则同步到后台根目录的产物会错误引用 `/jugg/assets/**`。首次发布前需要在 `tencentmusic/jugg` 的 `Settings -> Pages` 中将 Source 设为 `GitHub Actions`，再手工运行 `Deploy wiki to GitHub Pages` 或向 `main` 推送 Wiki 变更。GitHub 不会将仓库转移前的 Pages 地址自动重定向到新地址。

GitHub Pages 发布验证：

1. Actions 中 `Deploy wiki to GitHub Pages` 的 build 和 deploy job 均成功。
2. 打开 `/jugg/`、`/jugg/zh/` 和至少一个中英文正文页面。
3. 检查 CSS、JavaScript、字体和图片请求均位于 `/jugg/assets/**` 或对应 `/jugg/` 子路径。

---

## 9. 后台发布约定

后台项目 `jugg_backend` 约定以运行目录下的相对目录 `./wiki` 作为 Wiki 静态根目录。后台启动后不会缓存静态文件列表，发布脚本用 `rsync --delete` 更新该目录后，请求会读取最新文件。

后台路由约定：

```text
/      -> ./wiki/index.html
/wiki  -> redirect /zh/
其他路径 -> ./wiki 下同名文件、目录 index.html 或 .html 文件
```

`/` 必须保留给英文根页面，因为 VitePress 的英文 locale 默认位于根路径；`/wiki` 作为中文默认入口跳转到 `/zh/`。不要只把 `zh/index.html` 内容直接返回给 `/wiki`，否则 VitePress 客户端会按浏览器当前路径 `/wiki` 查找页面并渲染 404。

本机发布脚本：

```bash
~/Documents/shell/publish_jugg_wiki.sh
```

脚本执行内容：

1. 进入 `docs/wiki`。
2. 执行 `npm ci` 与 `npm run build`。
3. 校验 `docs/wiki/.vitepress/dist/zh/index.html` 存在。
4. 同步 production 产物到两台后台服务器：

```text
ali:/var/www/jugg_backend/wiki
yun:~/jugg_backend/wiki
```

其中 `ali` 的 `/var/www/jugg_backend/wiki` 通常需要 sudo 权限。脚本会先同步产物到登录用户 home 下的临时目录，再通过交互式 `sudo` 在服务器内更新 `/var/www/jugg_backend/wiki`，执行时可能提示输入 `ali` 用户的 sudo 密码。

发布前检查：

1. 在 `docs/wiki` 下执行 `npm run build` 成功。
2. 在 `docs/wiki` 下执行 `npm run preview` 检查中英文首页、nav/sidebar、搜索和新增页面。
3. 确认 production 产物不包含 `dev/elements-demo` 与 `zh/dev/elements-demo`。
4. 若改动了 nav/sidebar，同时检查英文根路径和中文 `/zh/` 路径。
5. 发布后检查后台 `/`、`/wiki`、`/zh/` 和至少一个静态资源路径。

---

## 10. 公开插件下载

正式版和 Canary 使用不同发布语义：

- 正式版由版本 tag 触发 `release.yml`；仅 tag commit 已包含在 `main` 时才会构建，每个版本创建独立 GitHub Release。
- `nightly.yml` 更新可移动的 `canary-nightly` tag，并覆盖 `Jugg Canary` prerelease。
- Canary 的 Actions artifact 只保留 14 天，用于构建排查；公开下载入口必须指向 GitHub Release asset，不能依赖 workflow run 页面。
- README 和 Wiki 使用固定的 develop Canary 地址，因此每次构建不需要更新页面链接。
- `release.yml` 必须排除 Canary tag，避免滚动 tag 被正式发布流程校验为版本号。

develop 有新 commit 时才会重新发布 Canary，版本为 `${baseVersion}-canary.<日期>.<run>`。Canary 可能包含未经完整验证的改动，下载页必须明确标记不稳定属性。

## 11. 关联文档

- `10_wiki_authoring.md`：普通 Wiki 文章写作规则。
- `docs/wiki/.vitepress/config.mts`：站点配置、路由、导航和 production 排除规则。
