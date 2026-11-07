# 自主推进恢复点：已暂停

目标：思维导图与文件关系网络，整体complete=false。用户于2026-11-07明确要求先停下保留进度，准备重构渲染架构；goal status=paused。不要自动续跑或继续实现。
工作树：/Users/kita/.codex/worktrees/mind-map/xnotes；分支codex/mind-map，保留供后续接续，不归档／删除。
授权：本次只保存现有改动、证据、交接和本地提交；不打包、安装、推送、合并或发布。
已完成提交：c5d0d4ac（B1b原位文本）、c93f8129（B2a保真升级）、0f0a8d91（B2b1身份模型/codec/PDF恢复）。0f0a8d91全量2808 tests，0失败/错误/跳过，Android仪器源码编译；实际仪器/设备未运行。
当前局部保存：B2b2a1新增BundleHeader/BundleSnapshot和测试，12项定向通过(tmp/bundle-header-boundaries.log，Header8/Snapshot4)，独立helper复核完成；最新helper未跑全量/Android，未接入writer。整包改UUID、BundlePdf/复制/登记/SAF/pending安全要求已审核但未实现。此WIP与交接同次本地提交，恢复时用git log读取保存点号。
运行工作：无已知Gradle作业；64985已成功结束。review_input_plan已完成只读复核并停止。不要重复启动旧作业。
恢复入口：docs/movinkpad/mind-map-rendering-handoff-2026-11-07.md，完整计划/待办/审核保留。先核对新渲染架构及最新规则/分支状态，重审原位/放大、文本覆盖层、输入仲裁、对象生命周期/snapshot、工具/撤销和双窗格假设，再修订计划。用户明确恢复后才继续；不主动合并重构分支。
2026-11-07 审查修复轮（Claude Code，经用户明确要求，不改变 paused 状态）：修复审查第1/3/4/5/6/7/8项并本地提交，全量2828 tests 0失败，Android仪器源码编译，未运行。第2项（锚定文档单个坏对象整份拒绝打开）为设计取舍待用户决定，第9项（主线程SAF显示名查询，master同有）未改。详见docs/movinkpad/mind-map-review-fixes-2026-11-07.md，其中含与master合并注意事项（master已合入画布不分屏2f6373b5，openInto/openSplit开头均有拦截）。恢复时先读该文件。用户同日另有方向：画布专供画画与草稿、不分屏；思维导图承接偏笔记的结构化需求，「导图为画布超集」是后续目标而非现有能力；建议渲染重构收缩范围，导图不必等待其全部完成——以用户最新指示为准。
未完成：B2b2、B2c、C1/C2/D及A2残余全部保留；完整引用闭环未完成。真实SAF/设备/GPU/延迟/软键盘/完整UI验收未执行。
