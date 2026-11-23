# 导图 v2 test1 打包与真机测试（2026-11-22）

用户要求：「打包到连接的movinkpad上，进行一下真机测试」——为 `mindmap-v2` 分支（画布内导图 UI 与阶段 2.5 后端）打包独立测试包，安装到连接的 MovinkPad 并做真机测试。测试中用户追问「节点怎么新建时有两个白框」。
状态：已打包、已安装、部分真机验证（手指 / 软键盘路径）；发现 1—4 已在源码修复并通过 JVM 测试，未装机复验；笔写入节点、橡皮擦节点墨迹、连线工具建关系待 Pro Pen 3 真机验证。
本轮边界：仅打包 test1 并安装到连接的设备；不覆盖正式版及其他测试包，不推送、不发布 Release。

## 构建

- 源码 `32d6dc53`（`mindmap-v2`），工作树 `<工作树 wt-mindmap-v2>`，JDK 21，独立 `--project-cache-dir`。
- 命令：`../gradlew --init-script scripts/branch-preview.init.gradle -PbranchTestId=mindmapv2 -PbranchTestLabel=导图v2 -PbranchTestVersion=1 :app:assembleDebug`（ARM64；分支测试包为 Debug 构建，原记录误写为 `assembleRelease`，2026-11-22 test2 时更正）。
- 产物 `XNotes-mindmapv2-32d6dc53-test1-arm64.apk`；SHA-256 见 [`../build-logs/mindmapv2-test1/SHA256SUMS.txt`](../build-logs/mindmapv2-test1/SHA256SUMS.txt)。
- 包名 `com.xnotes.movinkpad.branchtest.mindmapv2`，versionCode 1，versionName `0.9.10-test-mindmapv2-1`，名称「XNotes 导图v2 测试版」，仅 arm64-v8a。
- 签名证书 SHA-256 与 AGENTS.md 一致（`A0:45:3B:…:84:51`）。全新安装，未覆盖其他包。

## 真机结果

设备：Wacom MovinkPad Pro 14（DTHA140），Android 15，夜间模式；操作经手指触控与 Gboard 悬浮拼音键盘，未使用笔。

| 项目 | 结果 |
| --- | --- |
| 导图工具点空白建根节点、输入标题、键盘「完成」提交 | 通过 |
| 选中节点后菜单栏、子节点「+」手柄、菜单「添加子主题」 | 通过 |
| 拖动根节点到另一节点改父、撤销恢复、重做可用 | 通过 |
| 拖动根节点到空白处移动 | 通过 |
| 样式面板改填充色 | 通过 |
| 折叠 / 展开、折叠计数徽标 | 通过 |
| 返回首页后重开：位置、填充、层级保留 | 通过 |
| 夜间配色（白框黑底、黑字显示为白） | 通过 |
| logcat 无本包错误 | 通过 |
| 笔写入节点、橡皮擦节点墨迹、连线工具建关系 | 未测（需 Pro Pen 3） |

## 发现

1. **新建时两个白框**（用户反馈）：编辑标题时同时画了选中外框与输入框边框，且输入框撑到最大标题宽度（320），超出节点右侧。已修复：输入时不画选中外框，输入框不画边框；补 `aNodeBeingTypedIntoHasNoOutlineOrHandles`。修复未装机验证（需新包）。
2. **样式面板打开时按返回关闭了整个文档**，而非只关面板（面板为不可聚焦弹窗，返回键落到编辑器）。已修：菜单的面板打开时返回只关面板。
3. **画布缩略图不含导图节点**（首页缩略图为空白）。已修：导图部件保存绘制片段，GL 网格与 CPU 绘制同源；文件磁贴、页面缩略图、原生预览与截图区域都带上导图（`MapPaint`、`MapPaintTest`）。
4. 子节点很近时，父节点的子手柄「+」压在连线 / 子节点上。已修：侧边有相邻节点时手柄放在间隙中点（连线上），折叠时让开计数徽标；按下落在节点内时节点优先于溢出的手柄触控区。
5. 样式面板在夜间黑底上无边界；菜单图标按钮无无障碍标签（快照中为空按钮）。
6. 拼音键盘组字时文字留在键盘内，此时点画布会以空标题取消草稿，属输入法行为，非应用错误。
