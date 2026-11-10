# 固定 AAPT2 依赖的源码提交

Purpose: Remove release-tag movement from the standalone dependency selection.
Decision: Pin AOSP archive URLs to full commit IDs and retain archive SHA-256 validation.
Impact: Dependency source trees are unchanged; remote repackaging and availability still require preserved archives.

## 实施与验证

现有清单使用 android-14.0.0_r75 名称和压缩包 SHA-256。tag 仍是可变引用；压缩包字节也不能代替源码身份。通过各仓库的 Gitiles commit JSON 解析对应提交，将 13 份 AOSP URL 改为完整提交 ID。protobuf 保持原来的上游 release asset 和校验和。

先验证旧缓存归档符合原清单，再下载固定提交归档。逐项比较解包后的路径、条目类型、权限、符号链接目标及普通文件内容哈希：13 份源码树完全一致。部分归档字节哈希不同，已将新归档哈希与固定提交 URL 一同更新；本地缓存同步更新，14 份归档校验全部通过。未改变解压后的源码或构建逻辑，不增加实现细节自动测试；真实下载及完整源码树比较作为替代证据，无需重新编译。

源码提交锁定不覆盖远端打包算法变化或服务不可用。当前未配置自有归档镜像，build/deps14 也仍是可清理缓存；长期离线复现需要另行保存符合清单校验和的归档。不能通过自动接受新 SHA-256 绕过这一边界。

同步英文构建知识文档；未改变用户功能，无需 Wiki 更新。
