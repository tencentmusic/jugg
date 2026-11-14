# JGit Repository 生命周期修复与大仓库验证

日期：2026-10-15。

状态：补丁、定向测试与大仓库查询基准已完成。

## 问题与失败证据

[内存交接](jugg-memory-optimization-handoff.md)中的两份堆快照显示，Jugg 的 JGit `WindowCache` 保留树为 493.39 / 398.08 MiB。继续拆解后，实际窗口及数据只有 7.05 / 7.84 MiB，主要成本是同一个 JOOX pack 的索引被加载了 5 / 4 份，每份约 96.46 MiB。

`GitManager.repository` 每次访问创建 Repository，但 `getGit().use {}` 只关闭 `Git.wrap(repository)` 返回的包装对象。JGit 6.8 明确不通过这个包装对象关闭调用方创建的 Repository。缓存条目的 `pack` 字段继续强引用 `Pack.loadedIdx`，形成重复索引保留。配置读取与仓库存在性检查也没有关闭 Repository，`filterChangedFiles()` 同时创建两个实例。

本地基线再次复现：在 JOOX 大仓库重复进行 HEAD、diff、status、filter、历史内容查询后，缓存保留 128 个已加载索引，同一 pack 最多 4 份。

## 实施范围

- `GitManager` 使用私有 `withGit` 在 Repository 的 `use` 范围内执行查询，正常结束、异常和提前返回均释放仓库。
- 配置查询和仓库存在性检查释放 Repository；`Git.init().call()` 返回的 Git 对象也关闭。
- `filterChangedFiles()` 的 `IndexDiff` 和 `FileTreeIterator` 共用一个 Repository。
- 保持既有返回值、worktree builder 与 HEAD 解析方式，不调整 JGit 缓存配置。
- 更新公共工具知识库，并修正编译知识库中“缓存 Repository”的不准确描述。

## 验证设计

### 测试价值与回归 owner

失败证据来自真实堆快照、JGit 资源所有权契约及大仓库基准，不新增依赖私有字段、GC 时机或 mock `close()` 的单元测试，不向生产代码加入测试 seam。

复用已有行为 owner：`GitManagerTest`、`GitManagerWorktreeTest`、`GitFileChangesDetectorTest`。覆盖普通仓库和 worktree 的初始化、提交、差异、未跟踪文件、历史内容读取，以及 HEAD 变化补检。未修改编译或部署编排，不扩大到设备 Flow 测试；JOOX 工程未新增单元测试。

### 大仓库基准

- 仓库：`/Users/wormchen/IdeaProjects/joox/JOOX_Android_2`，固定 HEAD `c74a1d1272b60d47ae12f38dd1447a02a1e6c461` 与其父提交。
- 直接编译修复前后相同 Git 源文件集合，调用真实 `GitManager`；使用相同 Kotlin 1.9.23、JGit 6.8、JDK 与 JVM 参数。
- 每个版本启动 3 个独立 JVM，以旧、新、新、旧、旧、新的顺序交替执行；每个 JVM 运行 10 轮，前 2 轮预热，后 8 轮计入热查询统计，每类操作每个版本共 24 个样本。
- 同一轮依次读取 HEAD、相邻提交差异、工作区变化、指定文件过滤及历史内容；比较返回数量和排序后路径/内容的 SHA-256。
- 耗时包含操作内的 Repository 创建、读取和关闭；结果摘要计算、缓存检查与 GC 在计时窗口之外。记录首轮耗时，但不清理操作系统文件缓存，因此首轮不等价于磁盘冷读。
- 在所有查询完成后检查缓存持有的 Pack/索引数，再以相同显式 GC 条件记录独立 JVM 的堆 used。这是局部查询基准，不等价于 IDE 进程 RSS 或安装插件后的整体验证。
- 首轮试跑与 Gradle 编译重叠，仅作复现与接线检查；正式耗时统计在定向测试结束后执行。

## 验证结果

定向命令：

```bash
ANDROID_HOME=/Users/wormchen/Library/Android/sdk \
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
./gradlew :idea:test \
  --tests 'com.sickworm.intellij.jugg.git.GitManagerTest' \
  --tests 'com.sickworm.intellij.jugg.git.GitManagerWorktreeTest' \
  --tests 'com.sickworm.intellij.jugg.project.GitFileChangesDetectorTest'
```

3 个测试类共 22 项全部通过，无失败和跳过；该命令同时完成生产代码及测试代码编译。

正式热查询耗时中位数如下，单位为 ms：

| 操作 | 修复前 | 修复后 | 差值 |
| --- | ---: | ---: | ---: |
| HEAD | 1.25 | 1.34 | +0.09 |
| 相邻提交 diff | 76.19 | 78.91 | +2.72 |
| 工作区变化 status | 477.96 | 486.19 | +8.23 |
| 指定文件 filter | 90.04 | 87.02 | -3.03 |
| 历史内容 content | 75.02 | 77.66 | +2.63 |
| 完整一轮五类查询 | 723.99 | 731.73 | +7.74（+1.07%） |

完整一轮按各轮五类查询之和统计中位数，不等于各行中位数相加。两版耗时范围重叠；当前实测没有出现需要引入批次复用的明显耗时回退。固定序列中旧实现本来也反复创建 Repository，因此不能把旧实现误认为“全程共享一个热索引”。首轮是 JVM 首次查询，操作系统文件缓存未清理，不能声称完成磁盘冷读对比。

所有操作在所有轮次的返回数量和结果摘要一致。查询结束后的资源检查：

| 指标 | 修复前（3 组） | 修复后（3 组） |
| --- | ---: | ---: |
| 缓存持有的已加载索引数 | 每组 128 | 每组 0 |
| 同一 pack 的最多索引副本 | 每组 4 | 每组 0 |
| 窗口数据计数 | 8.19–8.74 MiB | 每组 0 |
| 相同 GC 条件下独立 JVM 堆 used 中位数 | 411.36 MiB | 3.85 MiB |

独立查询 JVM 的堆 used 减少约 407.52 MiB；该值不是插件自身保留集合，也不是 IDE RSS 的实测减少量。缓存检查在 GC 前后均为修复后零持有，不依赖 GC 清除软引用才释放资源。原 IDE 未安装补丁，因此安装后的 IDE 整体内存与端到端编译耗时不在本次验证范围。

额外在同一大仓库验证缺失 commit、缺失历史文件及 diff 抛出异常三个退出路径：原有 false 返回或异常语义保持，退出后窗口、缓存持有 Pack 和已加载索引均为 0。证据见本地 `failure-exits.log`。

JOOX 的 HEAD、原有已跟踪改动状态及原有改动文件 SHA-256 在基准前后相同。

## 文档与现场

同步 `05_utilities.md` 的 Repository 所有权及 JGit 内存解释，修正 `02_compile_core.md` 的 GitManager/Repository 缓存描述。仅涉及内部资源释放，没有改变用户操作、支持范围、公开配置或恢复步骤，无需更新 Wiki。

完整本地基准源码、固定输入、运行命令、逐次结果与测试日志保存在 `~/.jugg/investigations/jgit-lifecycle-20261015/`，不将商业工程材料加入 Git。原始堆快照及 MAT 拆解仍保存在 `~/.jugg/investigations/resource-usage-20261015/`。未替换当前 IDE 已安装的插件。
