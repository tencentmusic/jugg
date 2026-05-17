# ConstRef 全局缓存优化方案（跨项目 / 多 worktree）

更新时间：2026-02-22

## 1. 目标

针对常量重编译链路，落地以下三点：

1. `RepoSharedFingerprintStore` 和 `ConstRefCacheDatabase` 全局共享，支持同仓库、多工程、多 worktree 命中。
2. 全局 db 目录统一收归 `JuggPathManager` / `JuggGlobalPathManager` 管理，根目录为 `~/.jugg`。
3. 设计并实现 db 过期清理策略，避免长期膨胀。

说明：若文档与代码冲突，以代码为准。

---

## 2. 现状（以代码为准）

| 项目 | 现状 | 代码依据 | 差距 |
|---|---|---|---|
| `ConstRefCacheDatabase` 路径 | 每个项目独立，位于 `build/jugg/database/const_ref.db` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt:77` | 无法跨项目/worktree 共享定义与引用缓存 |
| `RepoSharedFingerprintStore` 路径 | 已全局，使用 `~/.jugg/const_ref/repo_fingerprint.db` | `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt` | 已统一到 Jugg 全局目录 |
| mtime 命中策略 | 文件 mtime 变更后，先尝试指纹命中；miss 才全量 CRC32 | `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt:289` | 缺少“同文件多 mtime -> 同 checksum”的显式索引 |
| 过期清理 | 无定期清理机制 | `ConstRefCacheDatabase.kt` / `RepoSharedFingerprintStore.kt` 当前无 cleanup 表和流程 | 数据会持续增长 |

---

## 3. 方案总览

### 3.1 全局路径统一（收归 JuggPathManager）

在 `JuggPathManager` 增加全局 const-ref 路径字段，由其统一管理：

- 根目录：`globalJuggRootDir = JuggGlobalPathManager.rootDir`，即 `~/.jugg`
- const-ref 目录：`constRefDir = File(globalJuggRootDir, "const_ref")`
- 文件：
  - `constRefSharedDbFile = File(constRefDir, "const_ref_shared.db")`
  - `repoFingerprintDbFile = File(constRefDir, "repo_fingerprint.db")`

说明：

- 路径解析集中在 `JuggPathManager`，避免各模块重复拼接 `PathManager`。
- 继续保留测试注入能力（可传入临时路径的 `JuggPathManager` 或直接传入测试 db 文件）。
- 运行时默认走 `JuggPathManager` 提供的全局路径，不再由 `DeployFileManager` 直接拼项目内 `const_ref.db`。

### 3.2 外部注入约束（db 管理类不感知 PathManager）

`ConstRefCacheDatabase`、`RepoSharedFingerprintStore` 仅接收外部传入的 `dbFile`（或目录）：

- 禁止在 db 管理类内部直接调用 `PathManager.getSystemPath()`；
- `DeployFileManager` / 上层组装器从 `JuggPathManager` 取路径并注入；
- 这样可保持 db 层纯粹，便于单测与离线工具复用。

### 3.3 统一仓库身份

复用现有 worktree 兼容逻辑（`repo_key` + `relative_path`）：

- `repo_key`：通过 `.git` / `commondir` 归一到同一个仓库根；
- `relative_path`：文件相对仓库根路径。

这保证同仓库不同 worktree 的同一文件可定位为同一逻辑 key。

---

## 4. ConstRefCacheDatabase 升级设计（共享分析缓存）

## 4.1 Schema v2（建议）

新增/调整表（核心字段）：

1. `file_checksum_mtime_map`
- `repo_key TEXT`
- `relative_path TEXT`
- `last_modified INTEGER`
- `checksum INTEGER`
- `updated_at INTEGER`
- 主键：`(repo_key, relative_path, last_modified)`
- 用途：记录“同文件多个 mtime -> 同 checksum”映射。

2. `file_analysis_head`
- `repo_key TEXT`
- `relative_path TEXT`
- `checksum INTEGER`
- `analyzed_at INTEGER`
- `last_access_at INTEGER`
- 主键：`(repo_key, relative_path, checksum)`

3. `const_definitions`
- 增加外键字段：`repo_key, relative_path, checksum`
- 复合唯一键：`(repo_key, relative_path, checksum, fq_class_name, const_name)`

4. `const_references`
- 增加外键字段：`repo_key, relative_path, checksum`
- 索引：`(repo_key, def_fq_class_name, const_name)`、`(repo_key, relative_path, checksum)`

5. `maintenance_meta`
- `key TEXT PRIMARY KEY`
- `value TEXT`
- 用于记录 `last_cleanup_at` / `last_vacuum_at`。

### 4.2 命中流程（满足“相对路径 + checksum + 多 mtime”）

对每个待分析文件 `F`：

1. 解析 `repo_key + relative_path + last_modified`。
2. 先查 `file_checksum_mtime_map`：
   - 命中：直接得到 checksum（不读文件内容）。
3. 若未命中，查 `RepoSharedFingerprintStore`（头/中/尾采样签名）：
   - 命中：得到 checksum，并回写一条新的 mtime 映射。
4. 若仍未命中：计算 CRC32 得到 checksum，并写入指纹库 + mtime 映射。
5. 拿到 checksum 后查 `file_analysis_head`：
   - 命中：直接复用已有 definitions/references（更新 `last_access_at`）。
   - 未命中：执行 AST 解析并落库。

关键点：同一 `repo_key + relative_path + checksum` 可对应多条 `last_modified`，从而覆盖 clone/worktree 的 mtime 漂移。

---

## 5. 受影响文件查询改造

现有实现基于绝对路径存储引用，跨 worktree 不可复用。改造为：

1. 存储层仅保存 `repo_key + relative_path`。
2. `getEffectedFiles(changedFilePaths)`：
   - 将 changed files 转为 `repo_key + relative_path`；
   - 查询受影响的 `relative_path` 集合；
   - 使用当前工作副本 repo root 还原绝对路径；
   - 仅返回存在的本地文件。

这样同仓库不同工作副本可共享分析结果，但返回仍是“当前工程可编译路径”。

---

## 6. RepoSharedFingerprintStore 优化

保持其“轻量签名 -> checksum”的角色，但做两点增强：

1. 路径由 `JuggPathManager.repoFingerprintDbFile` 注入（默认落在 `<system>/jugg/const_ref/repo_fingerprint.db`）。
2. 增加清理字段与索引（`updated_at` 已有）并接入统一 cleanup 调度。

备注：`RepoSharedFingerprintStore` 与 `ConstRefCacheDatabase` 分层不变，避免把快速签名库与分析缓存耦合成单一热点表。

---

## 7. 过期清理策略

### 7.1 触发时机

- 初始化时按节流触发：距离上次清理超过 `24h` 才执行；
- 清理在后台任务执行，不阻塞主分析路径；
- 清理失败仅打 warn，不影响增量编译主流程。

### 7.2 清理规则（建议初始值）

1. `file_checksum_mtime_map`
- 删除 `updated_at < now - 30d`；
- 且每个 `(repo_key, relative_path)` 仅保留最近 `20` 条 mtime 记录。

2. `file_analysis_head` 及子表
- 删除 `last_access_at < now - 90d` 的分析版本；
- 且每个 `(repo_key, relative_path)` 最多保留最近 `5` 个 checksum 版本。

3. `repo_fingerprint`
- 删除 `updated_at < now - 60d`；
- 每个 `(repo_key, relative_path)` 最多保留最近 `10` 条签名记录。

4. 物理收缩
- 每 `7d` 触发一次 `wal_checkpoint(TRUNCATE)`；
- 若 db 文件超过阈值（如 `256MB`），再执行 `VACUUM`。

---

## 8. 迁移与兼容

### 8.1 路径迁移

首次升级时：

1. 通过 `JuggPathManager.constRefDir` 创建新全局目录；
2. 旧路径存在则尝试迁移：
   - `~/.jugg/const_ref/repo_fingerprint.db` -> 新路径；
   - 项目内 `build/jugg/database/const_ref.db` 作为“可选导入源”（仅当前项目首次运行导入）。

### 8.2 版本迁移策略

- `ConstRefCacheDatabase` 使用 `PRAGMA schema_version`（参考 `DeployDataDatabaseSqLiteHelper` 风格）；
- 版本不兼容时重建，并写清晰日志；
- 所有迁移异常降级为“重建空库 + 继续执行”。

---

## 9. 落地阶段

### Phase A：路径与注入改造

- `JuggPathManager` 新增 `globalJuggRootDir/constRefDir/constRefSharedDbFile/repoFingerprintDbFile`；
- `DeployFileManager` 从 `JuggPathManager` 取路径并注入 `ConstRefCacheDatabase`、`RepoSharedFingerprintStore`；
- 移除 db 管理类内部默认路径逻辑（或仅保留 test-only 构造），统一走外部传入。

### Phase B：Schema v2 与查询 API 改造

- `ConstRefCacheDatabase` 引入 repo/relative/checksum 维度；
- `getEffectedFiles` 改为 repo+relative 查询并还原当前工作副本绝对路径；
- 保留旧 API 包装层，降低调用方改造面。

### Phase C：命中流程升级

- `ConstRefScheduler` 接入 mtime-map -> fingerprint -> crc32 三层命中；
- 命中统计日志化：`mtimeHit/fingerprintHit/crcMiss/analysisReuseHit`。

### Phase D：清理任务

- 引入 `ConstRefCacheCleaner`；
- 完成 TTL + 版本保留 + checkpoint/vacuum；
- 清理节流与异常降级。

### Phase E：测试补齐

- 多 worktree 同内容不同 mtime 命中；
- 多工程共享 analysis 命中；
- clone 后 mtime 变化命中（通过 fingerprint + mtime 回填）；
- cleanup TTL / 保留上限 / 文件收缩触发；
- 回归测试：常量受影响文件计算不变。

---

## 10. 验收标准（对应需求）

1. 同仓库多工程、多 worktree 下，内容未变但 mtime 变化可命中，且第二次命中不再读文件算 CRC32。
2. 运行时默认 db 路径由 `JuggPathManager` 统一提供，落在 `~/.jugg/const_ref`。
3. 清理策略可观测（日志）且可验证（单测），db 大小在长期使用下不线性膨胀。

---

## 11. 关键代码落点（计划）

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabase.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/RepoSharedFingerprintStore.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefScheduler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/JuggPathManager.kt`
- 新增建议：
  - `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheCleaner.kt`
