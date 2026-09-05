# Duels / StrikePractice 适配

适配已安装的 StrikePractice 4.0.0-SNAPSHOT、Citizens 2.0.41 build 4134 和 Paper 1.21.11。其他服务器没有 StrikePractice 时不会加载此适配。

## 节点与部署

Duels 使用独立的 `server-id: duels`、`node-id: duels-1`，角色仅为 `gameplay`。数据库连接沿用群组服配置，不能复制大厅的业务权威角色或生存服的经济来源角色。Worker 的 `NODE_KEYS_JSON` 还需登记节点对应的桥接密钥；数据库连接成功不代表 Worker 桥接已经成功。

同一构建可替换现有 Paper / Velocity 节点的 JAR。运行中的节点应原子替换文件，待正常重启再加载，不能批量重启有玩家的服务器。旧版 1.8.8 BedWars 不属于此插件的兼容平台。

SQL 迁移文件在 `.gitattributes` 固定为 LF，避免 Windows checkout 改变迁移校验和。不要通过修改生产数据库迁移记录来绕过校验。

## 隐身与对局

适配每 tick 读取 StrikePractice 当前有效战斗状态。隐身玩家对同一场战斗内的其他战斗成员显示实体，同时从其玩家列表移除；回到大厅或结束对局后恢复隐藏。其他场次与大厅玩家不会因此看到隐身玩家。原有 `cctsystem.vanish.see` 权限保持其管理员可见语义。

对战中停止隐身的静默容器克隆行为，以便正常取用箱子；移除 CCTSystem 自己添加的夜视效果。此适配不赋予无敌或飞行。

## 关闭背包保存套装

只处理玩家主动关闭自己的 `CRAFTING` 物品栏，不把套装选择菜单、箱子、铁砧或程序触发的关闭当作保存请求。下一 tick 再确认玩家仍在编辑同一套装，调用 StrikePractice 原生 `kiteditor save`，确认生成新的个人第一套布局后再调用 `kiteditor leave`。因此沿用原生物品校验及个人套装存储，不修改全局 `kits.yml`。

服务器配置 `auto-give-first-edited-kit: ['*']`，使个人第一套布局在以后对战时自动应用。

## 机器人

当前 StrikePractice 的现代机器人依赖此 Citizens 版本已经移除的旧 GoalController API，因此保留兼容的旧机器人模式。只对 StrikePractice 注册的机器人处理 Citizens NavigationBeginEvent，同时更新默认和本次导航参数，防止更换目标后速度回退。普通 Citizens 展示 NPC 不受影响。

`plugins/CCTSystem/practice.yml` 可设置 `bot-speed-multiplier: 1.3`，允许范围 0.5–2.0。目标更新采用 Citizens 寻路器。服务器需设置 `main-thread-bot: true`、`fix-bot-kb: false`；CCTSystem 在有效伤害后的下一主线程 tick 对机器人做有上限的击退补偿，替代原插件异步操作实体速度的路径。

## 验证范围

2026-09-05：Gradle 全项目测试及统一 JAR 构建通过。Duels 实机临时探针验证 10 个竞技场、BedWars 大厅转换后出生点周围 3×3 安全空间、机器人 3 秒移动 10.81 格，以及切换导航目标后速度仍为 1.3。探针已禁用并移出插件目录。

隐身同场/异场隔离策略已有单元测试。真实客户端关闭背包后的个人布局持久化、两个真实玩家的隐身决斗和击退手感仍需游戏内验收；不能用启动成功或模拟导航代替这些验收。
