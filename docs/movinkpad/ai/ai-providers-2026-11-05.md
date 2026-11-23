# 多模型供应商（2026-11-05）

用户要求：同时使用多个模型供应商；聊天输入栏的下拉菜单可选择不同供应商及其模型；设置中按供应商分别填写 URL 与模型，配置与界面逻辑参考 DeepSeek Harness（DSH）。
状态：源码已实现，未打包。JVM 单元测试全部通过（2607 项，含新增 `AiProvidersTest` 14 项）；模拟器（API 32 ARM64，2160×1600 覆盖分辨率）检查了旧配置迁移、添加供应商、分组默认模型菜单、保存以及输入栏切换供应商。真实供应商请求、MovinkPad 真机交互待验收。
本轮边界：未打包。

## 参考

- DSH `deepseek-ai/deepseek-harness` 提交 `fcc0ad46`：`packages/llm/llm-pi-ai`（`providers` 字典：每个路由有 `displayName`、`baseURL`、密钥、协议、`compat.thinkingFormat`、`models`；`GET {baseURL}/models` 只给出候选，不自动保存）、`docs/user/guide/providers.md`（「添加模型供应商」、Provider ID 永久不变、获取模型后勾选更新列表、保存前不存储、删除供应商后会话显示「选择模型」并阻止发送）、`packages/client/ui-model-selection`（菜单按供应商分组、模型较多时显示搜索）。
- 独立实现，未复制代码；外观沿用本项目黑白灰阶、Tokens 字号间距和 48 dp 触控区。

## 与 DSH 的对应与差异

| DSH | XNotes |
|---|---|
| 用户输入的小写 Provider ID，永久不变 | 自动生成、不显示的 UUID，同样永久不变；用户只编辑显示名称 |
| 三种协议（Chat Completions / Responses / Anthropic Messages） | 只支持 OpenAI Chat Completions（含图片），设置说明中写明 |
| 内置供应商目录 | 预设菜单只填名称与 URL（DeepSeek、OpenAI、OpenRouter、阿里云百炼、Kimi、智谱、硅基流动、Gemini），Key 与模型仍需自己填 |
| `compat.thinkingFormat` 按路由 | 思考参数格式移到每个供应商；默认思考深度、最大输出长度、提示词、联网密钥仍为全局 |
| 在选择器选模型同时改变默认 | 保持本项目原约定：会话内的选择只影响本会话，默认在设置中修改 |

## 数据与迁移（`ai/AiConnection.kt`）

- `AiProvider(id, name, url, key, models, thinkingFormat)`；`AiConfig` 新增 `providers` 与默认供应商 `provider`。`AiConfig` 的 `url`/`key`/`models`/`thinkingFormat` 仍表示当前使用的路由，由 `route(id, model)` 填入，因此传输与请求构造无需改动。
- 加密配置 `ask-ai.config` 新增 `providers` 数组与 `provider`。旧配置（顶层 `url`/`key`/`models`）读取时迁移为一个 ID 固定为 `default` 的供应商，迁移结果确定，不在读取时写盘。
- 保存时仍在顶层写入默认供应商的 `url`/`key`/`model`/`models`/`thinkingFormat`，降级到旧版本时默认供应商照常可用（其他供应商对旧版本不可见）。
- 校验（保存前，失败保留原文件）：至少一个供应商、最多 20 个；每个供应商须 HTTPS 地址、Key 和至少一个模型，模型最多 200 个；默认模型须属于默认供应商。错误消息带供应商名称。
- 编辑或删除供应商后，`withValidDefault()` 让默认值仍指向存在的供应商与模型。

## 会话（`AiHistoryStore.kt`、`AiSessions.kt`）

- 会话记录新增 `provider` 字段（与 `model` 同存）。旧记录没有该字段：模型由默认供应商列出时走默认供应商，否则走第一个列出它的供应商，都没有时走默认供应商。
- 会话所选供应商被删除后：输入栏胶囊显示「选择模型」，发送按钮禁用；若仍触发发送（如重试），该轮以「此会话所选的供应商已删除…」失败，不发出任何请求。
- 回答请求、PDF 工具步骤与联网工具轮次都使用会话所选供应商的地址、密钥与思考格式。

## 界面（`ui/AskAi.kt`）

- **AI 设置**：「模型供应商」卡片列出各供应商（名称、主机、模型数量，多于一个时默认者带「默认」标记），点击进入编辑；下方「添加模型供应商」。「新会话默认」的默认模型菜单按供应商分组。原「供应商 / 模型」分组和全局参数格式移入供应商编辑页。
- **供应商编辑页**：预设、显示名称、URL、API Key；模型列表（手动添加、移除、「从供应商获取」后搜索勾选）；思考参数格式；已有供应商可删除（就地二次确认）。「完成」只修改设置草稿，点击 AI 设置的「保存」才写入。
- **输入栏胶囊**：多个供应商时显示「供应商 · 模型 · 深度」；菜单按供应商分组列出模型，总数超过 8 个时显示搜索框。
- 新增字符串已提供英文与两套简体中文资源。

## 待验证

- 真实供应商：多个供应商之间切换发送、各家 `/models` 返回、预设地址是否可直接使用（尤其 Gemini OpenAI 兼容端点与智谱 v4 路径）。
- 模拟器未逐项点验：删除供应商后会话显示「选择模型」（JVM 测试覆盖逻辑）、模型超过 8 个时的菜单搜索、预设与已填名称的覆盖规则。
- MovinkPad 真机：嵌套对话框与键盘、菜单触控。
