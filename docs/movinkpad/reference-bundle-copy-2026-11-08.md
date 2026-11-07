# 页面引用整包复制与导出（2026-11-08）

用户要求：持续推进原生思维导图与文件关系网络；不打包APK，允许编译和运行测试。先接通原字节页面身份替换与已有复制/导出入口。
状态：R1b1已实现；全量2999项JVM、Android测试源码编译通过，独立复核完成。实际仪器/SAF/目标设备未运行。
本轮边界：源码、验证、文档和本地提交；不打包、安装、推送、合并或发布。

## 行为

- BundleMigration、BundlePdf和Editor.copyFileTo只从同一BundleSnapshot读取Header与内容。InputStream直接捕获一次，避免再次复制大型PDF包；File入口返回前再次核对外部原件摘要。
- 已有页面引用的独立整包副本生成新文档和页面UUID，并重绑marker；旧页面编号只在直接pages的字符串字面量处替换，笔迹/未知对象/扩展负载原字节保持。
- 同文档便携导出保留页面身份、manifest和原marker字节，但也完整校验页面契约；缺失/重复页面ID或page marker+xcanvas会拒绝。
- BundlePdf.prepare核对snapshot原文档UUID，拒绝把共享相同PDF的另一份笔记绑定为原身份。复制/包装不能更换源包已记录的PDF指纹；源无记录但有嵌入PDF时，新指纹须匹配原负载。借入PDF保持resolve/lease约定，并在摘要/CRC/复制环节响应取消、验证内容。
- 源和候选均核对中心目录与顺序local视图，拒绝重复/孤立local条目。锚定源混有external PDF与嵌入PDF时拒绝，不以导出自动替换底稿。legacy保留既有非锚定容错与大token负载能力。
- Header的comment/Zip64回退在跳过manifest前筛声明尺寸，并计数实际解压量；不会先无上限解压再校验。Header/legacy短扫/复制/摘要与页面ID生成可取消。失败只清理私有候选、manifest和快照，不删除共享PDF。

## 验证及修复证据

新增14项PageBundleCopyTest及1项BundleHeaderManifestLimitTest。初始6项范围中的复制/标记/同身份损坏页/PDF迁移错配反例在旧实现失败（tmp/page-bundle-copy-red.log），基础工具与入口接线后定向通过。

独立复核发现并接受三项修正：Header回退限流顺序、锚定external+embedded PDF歧义、legacy大前缀取消。tmp/page-bundle-review-red.log先复现前两项，tmp/legacy-format-cancel-red.log先复现第三项。另补PDF指纹同时替换目标参数/文件的反例tmp/pdf-copy-fingerprint-red.log，再以源记录约束目标。所有红灯在修复后回归通过。

测试包括真实压缩512MiB+64KiB manifest（声明和伪中心目录尺寸的实际解压限流）、孤立local manifest、源变化/中途取消、A→B→A期间只取snapshot内容、stream快照证据边界、共享PDF保护、原marker字节与复制页ID的独立字节预期。

最终命令沿用JDK21、Gradle Wrapper和独立输出/项目缓存，执行`:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`，日志tmp/page-bundle-copy-full.log：278套件2999项，失败/错误/跳过均0。新增Android仪器源码覆盖真实平台ZIP/codec页面复制与同文档便携导出；仅编译，不冒充实际运行。

最终独立只读复核确认所提问题均解决，未发现新的确定性阻断；实际SAF、Android仪器、窗口、GPU与MovinkPad Pro14/Android15/ProPen3未运行。

## 继续范围

这不是页面来源闭环完成。R2仍须处理登记表单调页契约、身份替换显式恢复、pending日志/保存/in-place预检查及活模型接入；用户文件尚未自动启用页锚点。

原始整包路径已接通；Editor.save/saveToThen、forkNote和合并笔记中的活模型文档UUID切换还需独立设计新页面身份、保存成功后的会话切换/历史及迟到写入保护，不能简单修改既有ReferenceIdentity，也不能让下一次保存把新页ID改回。先完成这些保护再开放页面引用。

随后继续页面多来源入口/预览、来源往返、反向引用、双窗格与文件网络；既有需求不取消。
