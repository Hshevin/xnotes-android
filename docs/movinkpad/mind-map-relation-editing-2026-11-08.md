# 普通关系线端点与事务编辑（2026-11-08）

用户要求：持续实现思维导图；普通关系支持类型、方向、说明、端点编辑，重复线定位已有线、取消不留数据，一次连线修改一步撤销。
状态：A2普通线事务/端点编辑已实现；完整298套件3257项JVM及Android源码编译通过，最终文案/界面改动后12项定向回归及Android源码编译通过；独立代码复核通过，真实交互/目标设备未运行。
本轮边界：原生Kotlin源码、测试、文档、本地提交；不打包、安装、推送、合并或发布。

## 切片和理由

来源保存/恢复正在推进，尚需配套producer及另存持久切换，不能半接会自动覆盖原件的入口。本片同时补齐独立且已明确的A2普通关系线编辑能力，不取消R2/R3来源闭环或更改其实施依赖。保留现列表入口，边缘拖线、画布点选连线、端点拖柄和空白处建节点后续继续。

- 使用现有GraphRelationsDialog、MapRelation/MapHistory/.xmap v2，不引入引擎或格式版本。
- 编辑界面增加起点、终点节点选择，给出明确语义句：A与B关联（无方向）、A引用自B、A属于B、A依赖于B；方向交换只对有方向类型启用。未命名/手绘/引用节点显示现有本地化类别占位，选择列表可区分同名节点（按文档顺序编号，不把内部ID作为标题）。
- 草稿保存稳定的初始edge与拟修改edge；类型/方向/端点/说明在确认时一次性提交，输入和选择不进入历史。新连线也先草拟，取消不留下已连但未确认的边。
- 纯模型事务接受latest doc，实际UI经MapSession.changeRelation读取latest而不是拼整份doc提交，initial与draft分开且edge.id不变：编辑时初始edge缺失/已变化则拒绝覆盖；无关节点/来源/其它线变化保留。self-loop、缺失端点、超限说明/关系数拒绝且不写文档。删除只删普通连线，不动节点/层级，删除同样核对initial。弹窗期间端点/edge被删时显示失效占位并禁确认，不调用doc.node()导致重组崩溃。
- 同端点/同类型/同方向重复，undirected无序去重：不创建、不修改原线，转到已有线编辑并显示提示；不覆盖已有线说明。E1撞E2时两条原线不动，丢弃E1草稿，从latest E2重新构造initial/draft。无向端点交换视为语义no-op，有label变化仅记真正变化；4000条满额仍允许重复定位和原线编辑。取消不会留下残余数据。仅定位/换选无history；修改和删除一次commit、一次撤销，原节点/来源/笔迹保持。
- 样式继续黑白、TextSize/Space/MinTouchTarget token、三份字符串一致。所有触控项至少48dp，端点列表可滚动，取消/返回逻辑明确；无宽度固定像素。

## 验收

JVM真实模型/history/codec：从A→B改为A→C、方向交换、关系说明与类型一步提交/undo/redo；重复创建/编辑定位原线且无模型/history变化；无方向交换次序去重；不存在/删除端点、自环、过期edge不改文档；无关修改可合并；新连线取消没有数据。保存/重开保持端点/类型/说明，普通关系不改变层级。

Android保留真实Dialog草拟/确认/取消/重复定位的交互用例（工具可用性评估后选择既有Compose或事件测试方式）；编译与真实运行分别报告，不以JVM替代Android交互/目标设备验收。文件来源恢复和所有其余A2需求继续，overall complete=false。

## 交付与验证

GraphRelationsDialog已经改接MapSession.changeRelation，由owner从latest doc进行事务；MapRelations提供纯模型结果，Applied才commit。新线先草拟、取消零写入；编辑的端点/类型/说明/方向一次提交。E1撞E2时模型保持两线，Duplicate结果携带latest E2全对象，界面按该对象重建token草稿。取消或离开对话框使alive失效，迟到确认/IME不能提交旧草稿；字段合并只作用于仍活跃的同token。

列表节点显示顺序编号和标题（空标题用文本/手绘/引用类别），端点缺失显示占位；交换方向只对有方向类型启用。无向端点互换归一为原顺序，标签无变化时history仍为空。英文方向在独立复核后修成“A references B”，与中文“A引用自B”一致，不改持久化enum；两份中文graph.xml逐字一致，三语言53键一致、无重复键。

证据：`tmp/map-relation-editing-full.log`（JDK21/SDK36/Gradle8.13独立build/project cache、离线全量测试与Android测试源码编译）298套件3257项，0失败/错误/跳过。新增7纯模型方法和2真实MapSession方法；首批定向通过。最后英语/单节点按钮/迟到超限IME小改后 `tmp/map-relation-editing-final.log`：RelationEditingTest7项+MapSessionTest5项通过，Android源码编译成功。`git diff --check`及XML三语言键/中文一致检查通过。

新增MapRelationEditingHostTest三个实际Activity/Compose accessibility场景（取消新线、端点/方向/一步undo；重复定位保留说明；端点删除失效）加入现runner，仅源码编译，未运行。无APK/安装/设备运行，不能据此认定真实触摸、IME、窗口或MovinkPad兼容。

仍未交付：画布点选/边缘拖线、连线命中与拖端点、空白处新节点并连接、无中心模式、多选等；来源producer/新建另存持久切换、页面来源闭环、backlinks和文件网络继续保留。
