基于刚才 60 题的实测，以下是我对 layout_verify            
工具的综合评价。
                                                            
---                                                       
总体评价

比解析 layout_dump                                      
更好用，但存在几个影响准确性的设计问题。

核心优势是：agent 不需要自己解析 JSON、做单位换算、写比较
逻辑，工具替代了大量机械工作。但有几处设计缺陷导致了 6
道题答错，需要修复。

  ---
具体问题

问题一：数值比较时 expected 异常显示为 0（严重影响可读性）

现象：bounds.width, bounds.height, bounds.right 等属性的
gte/gt/lt 比较，工具返回的 data.expected 始终是
0，而非传入的 value。

// 我传入 value="100", unit="dp"
// 工具返回:
{ "result": "PASS", "actual": 379, "expected": 0, "unit":
"dp" }
//                                           ^^^ 应该是
100

这不影响 result 字段的 PASS/FAIL 判断，但 agent
和人工审查都无法从 expected
字段确认意图是否正确。单位换算是否生效也无法验证。

  ---
问题二：正则匹配行为异常（导致 A-08 答错）

现象：tv_mcp_action_state 文本为 "Waiting for
interaction..."，正则 Waiting.*interaction
理论上完全匹配，但工具返回 FAIL。

根据工具返回的 message：
FAIL: text = "Waiting for interaction..." (expected:
matches "Waiting.*interaction")

结论和 actual 值都正确，但 result=FAIL 是错的。怀疑 .*
中的 . 被转义处理了，或正则引擎不支持 .*
的贪婪匹配。这是一个严重 bug，因为 agent 看到 FAIL
就会认为不符合需求。

  ---
问题三：alignment(horizontal) 语义与直觉相反（导致
C-04/C-05/E-08 共 3 题答错）

现象：两个 match_parent 按钮，水平宽度铺满屏幕，中心 x
坐标完全相同。但工具报 FAIL：
FAIL: alignment (horizontal): vertical centers: 1031 vs
1189

根本原因：工具把 direction=horizontal
理解为"检查两元素是否水平排列（同行）"，而非"检查两元素在
水平方向上的中心是否对齐"。

设计稿说的"水平居中对齐"指的是
两者水平轴中心（centerX）相同，而工具检查的是
垂直中心（centerY）是否相同（即是否同行）。语义完全相反。

建议：

┌───────┬──────────┬───────────────┬─────────────────┐
│ direc │ type=ali │   当前行为    │    期望行为     │
│ tion  │  gnment  │               │                 │
├───────┼──────────┼───────────────┼─────────────────┤
│ horiz │ alignmen │ 检查 centerY  │ 检查 centerX    │
│ ontal │ t        │ 是否相同（同  │ 是否相同（竖排  │
│       │          │ 行）          │ 但左右对齐）    │
├───────┼──────────┼───────────────┼─────────────────┤
│ verti │ alignmen │ 检查 centerX  │ 检查 centerY    │
│ cal   │ t        │ 是否相同（竖  │ 是否相同（横排  │
│       │          │ 排中心对齐）  │ 但上下对齐）    │
└───────┴──────────┴───────────────┴─────────────────┘

目前行为与参数名含义刚好相反，非常容易误导 agent。

  ---
问题四：className 过滤使用精确匹配，不支持子串（导致 D-04
答错）

现象：传入 className: "TextView"
找不到任何元素，因为实际类名是 AppCompatTextView。

设计稿说的"className 包含 TextView"是很自然的需求，Android
有大量继承自标准类的 AppCompat 变体。className
过滤应支持子串匹配，或至少在找不到时给出包含 "TextView"
子串的候选列表。

  ---
问题五：spacing 测量的是视觉边界距离，而非布局 margin

现象：tv_mcp_title 和 btn_mcp_unique_text 之间视觉距离
216dp，但设计稿写的是 marginTop=20dp。

这不是 bug，而是工具的功能边界说明不清楚。spacing
计算的是两个元素 bounds
之间的像素差，而不是布局系统中定义的
margin。如果中间有其他元素（如 ScrollView），这个值会和
margin 差很多。

建议：在文档或工具 message 中明确说明：spacing =
两个元素在屏幕上边界之间的实际距离，不等于布局 margin。

  ---
[EXT] 控件缺失问题

E-01/E-02/E-05/E-11 共 4 题所需的扩展控件（tv_mcp_colored_
text、layout_mcp_alpha_bg、tv_mcp_long_text、btn_mcp_show_
dialog）在当前测试页面不存在。这是测试环境问题，不是工具问
题，但答案键按照"控件存在"来设计，导致这 4
题无法正常验证。

  ---
对工具设计的建议汇总

┌────────┬─────────────────────────────────────────────┐
│ 优先级 │                    建议                     │
├────────┼─────────────────────────────────────────────┤
│ P0     │ 修复正则 matches 返回错误结果的 bug         │
├────────┼─────────────────────────────────────────────┤
│ P0     │ 修复数值比较时 expected 字段始终为 0 的 bug │
├────────┼─────────────────────────────────────────────┤
│        │ 修复 alignment                              │
│ P1     │ 方向语义：direction=horizontal 应检查       │
│        │ centerX 对齐，而非 centerY                  │
├────────┼─────────────────────────────────────────────┤
│ P1     │ className 过滤改为子串匹配（或新增          │
│        │ classNameContains 参数）                    │
├────────┼─────────────────────────────────────────────┤
│ P2     │ 在 message 中说明 spacing                   │
│        │ 是屏幕边界距离，非 layout margin            │
├────────┼─────────────────────────────────────────────┤
│ P2     │ 增加 neq（不等于）操作符，E-03              │
│        │ 这类"颜色不能为X"的需求目前只能反推         │
├────────┼─────────────────────────────────────────────┤
│ P2     │ 对 dumpFile 模式，当 assert 传入具体 value  │
│        │ 但 expected 显示为 0 时，输出一个 warning   │
└────────┴─────────────────────────────────────────────┘

  ---
总结

layout_verify 相比直接解析 layout_dump JSON
的核心价值在于：声明式断言、自动单位换算、关系计算。这些对
agent 非常友好。主要弱点是几个语义歧义和 bug 会导致 agent
得出错误结论。修复上述 P0/P1
问题后，工具的可靠性会大幅提升。



一、参数传递

1. assert.value 应支持数字类型

目前 value 是字符串，数值比较时需要传 "100" 而非 100。结合
expected 字段返回 0 的 bug                             
来看，根本原因很可能就是字符串到数字的类型转换失败了。

建议 schema 改为 string | number，或在工具内部做安全的
parseFloat，并在 data.expected
字段回显转换后的数值，让调用方可以确认意图被正确理解了。

  ---
2. assert 的 op 字段默认值不清晰

exists 检查不需要传 op，但其他属性检查必须传。当前文档没有
明确说明各属性支持哪些 op，比如：

- visibility 支持 eq，但支持 neq 吗？
- alpha 支持 gt，但支持 eq 吗（浮点精度）？
- bounds.width 支持 gte，但可以不传 unit 吗？

每次都要靠猜和试。建议工具在参数非法时，errorCode=MCP_INVA
LID_PARAMS + 明确说明"该属性支持的 op
列表"，而不是静默执行或返回奇怪结果。

  ---
3. relation 参数的 expected/tolerance 省略行为不一致

- type=overlap：不需要 expected/tolerance，这合理
- type=alignment：不需要 expected/tolerance，合理
- type=spacing：需要
  expected/tolerance，但省略时工具不报错，而是用 expected=0,
  tolerance=0 静默执行

第三种行为是隐患。tolerance=0 意味着精确匹配，传入
expected=12 和不传 expected
的含义完全不同，但工具不区分。建议 spacing 缺少 expected
时返回 INVALID_PARAMS，或者在 message 中明确提示"未传
expected，默认为 0"。

  ---
二、调用顺序

1. dumpFile 的生命周期管理是最大的心智负担

每次交互后 dumpFile
就过期了，但工具本身不知道。实测中我多次需要警惕：

tap → layout_dump(新路径) → layout_verify(新路径)

如果不小心复用了旧的 dumpFile 路径，工具会正常返回结果但数
据是错的，不会有任何警告。这是最危险的静默错误。

建议两个方向：
- 方向 A：dumpFile 接受 "latest"
  关键字，工具自动使用最近一次 dump（不需要 agent 传路径）
- 方向 B：dumpFile 中嵌入时间戳，工具在文件超过 N 秒时输出
  warning："此 dump 已有 Xs，建议重新 layout_dump"

  ---
2. 初始化流程太长，适合封装为约定

每次测试都要走：restart_app → tap(导航) → layout_dump →
verify，共 3-4 个串行调用才能开始第一个验证。

对于 agent 来说，这个流程应该在 system prompt
或工具文档里有一个标准的"测试前置步骤"模板，否则每次 agent
都要自己推断顺序。

  ---
3. 串行约束应在工具层面给出更清晰的反馈

文档写了"MCP 同时只支持一个客户端调用"，但如果并发调用了会
发生什么？是排队、报错，还是返回脏数据？实测中我严格串行所
以没触发，但这个边界行为对 agent 来说是黑盒。

  ---
三、实际开发使用

1. 缺少批量断言能力，多属性验证很繁琐

C-12 需要同时验证 clickable 和 text，D-09 需要同时验证 3
项，E-07 需要 5 次调用。每次都是独立的工具调用，上下文消耗
大，且中间状态变化风险高。

建议支持 asserts 数组：

{
"target": {"resourceId": "btn_mcp_unique_text"},
"asserts": [
{"property": "clickable", "op": "eq", "value":
"true"},
{"property": "text", "op": "eq", "value": "Unique MCP
Target"},
{"property": "visibility", "op": "eq", "value":
"visible"}
]
}

返回每项的独立结果，整体 result 取最差值（任一 FAIL 则整体
FAIL）。这样 1 次调用替代 5 次，对 agent 的 token
消耗友好很多。

  ---
2. dumpFile 模式 vs 实时模式的选择对 agent 来说不直观

目前规则是：
- 需要 textSizeSp、maxLines 等属性 → 必须实时模式（不传
  dumpFile）
- 其他属性 → 推荐 dumpFile 模式

但工具本身没有在 dumpFile 模式下明确告知"该属性不支持，请
用实时模式"，而是直接报错或返回奇怪值。

建议：dumpFile
模式下遇到只有实时模式才能查询的属性时，返回一个专门的
errorCode，比如 MCP_LIVE_QUERY_REQUIRED，并说明原因。

  ---
3. candidates 列表在调试时非常有价值，应更充分利用

元素找不到时，工具会返回
data.candidates，这个设计很好。但目前候选列表只有 5
条，且没有相似度排序依据（为什么推荐这 5 个？）。

建议 candidates 按照字符串相似度（resourceId
编辑距离或前缀匹配）排序，并在 message
中说明推荐逻辑，帮助 agent
快速判断是否是拼写错误还是控件真的不存在。

  ---
4. 没有"仅读取值"的模式

很多时候我想知道某个属性的当前值，但 layout_verify
强制要求传 assert 或
relation。为了查询实际值，我不得不传一个 dummy 断言（比如
op=eq, value=0）然后从 FAIL 的 data.actual 里读出真实值。

建议增加 assert.property 只传 property 不传 op/value
时，工具返回该属性的当前值（纯读取模式），result 设为
"INFO" 或类似标识。这对调试和探索性验证很有用。

  ---
总结对比

┌───────┬─────────────┬──────────────────────────────┐
│ 维度  │    优势     │          主要改进点          │
├───────┼─────────────┼──────────────────────────────┤
│ 参数  │ schema      │ value 类型、op               │
│ 传递  │ 结构清晰    │ 缺省、relation 省略行为      │
├───────┼─────────────┼──────────────────────────────┤
│ 调用  │ dumpFile    │ dumpFile                     │
│ 顺序  │ 复用减少网  │ 过期无感知、初始化流程无模板 │
│       │ 络开销      │                              │
├───────┼─────────────┼──────────────────────────────┤
│ 开发  │ PASS/FAIL/E │ 缺批量断言、模式切换不透明、 │
│ 使用  │ RROR 三态结 │ 无纯读取模式                 │
│       │ 论直接      │                              │
└───────┴─────────────┴──────────────────────────────┘

整体来说，工具的设计思路是对的，声明式验证对 agent 比解析
JSON 友好得多。但目前更像是一个 v0.x
版本，修复上述问题后可以达到生产可用的水平。