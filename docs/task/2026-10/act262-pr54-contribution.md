# act262 Native 动态库热更新贡献补记

- 贡献者：[act262](https://github.com/act262)

## PR 与贡献内容

| PR | 状态 | 内容 |
| --- | --- | --- |
| [#49](https://github.com/tencentmusic/jugg/pull/49) | 已关闭，未合入 | 提出 `.so` 热更新方案，减少大体积 APK 重新打包、签名和安装的耗时。 |
| [#51](https://github.com/tencentmusic/jugg/pull/51) | 已合入，提交已进入 `main` | 引入 `.so` 热更新，失败时自动降级为重新签名安装。原始提交：[cda8bd8](https://github.com/tencentmusic/jugg/commit/cda8bd8a372569ead007fc9b4af0234caec6de51)。 |
| [#54](https://github.com/tencentmusic/jugg/pull/54) | 已合入，提交已进入 `main` | 引入基于校验和的变更检测，仅推送实际发生变化的 `.so` 文件，减少传输耗时。原始提交：[4e66097](https://github.com/tencentmusic/jugg/commit/4e660972283feb32c689f8bce878476bc0dbbbf5)。 |

其中 #49 仅记录提案，不计为已合入代码贡献。以上状态核对于 2026-10-25。

## 补记原因

已合入的 #51、#54 原始提交均使用 `chaoxizhang@tencent.com`，排查时 GitHub 未将该邮箱关联到 act262，导致 Contributors 未显示其账号。

本次通过新增贡献记录，并使用其 GitHub 公开邮箱 `act262@gmail.com` 作为提交作者邮箱，补充账号贡献记录；原始提交及历史保持不变。GitHub 上的归属与统计需在提交推送至默认分支后确认。
