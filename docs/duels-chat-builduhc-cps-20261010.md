# Duels 聊天、BuildUHC、CPS 修复

聊天根因：ChatFormatter 在 AsyncChatEvent 的 LOWEST 优先级安装渲染器，CCTSystem 的 PaperDuelChatRenderer 在 HIGHEST 替换它。旧渲染器以身份组件作为根节点追加消息，导致管理员红色和普通玩家灰色继承到正文；因此只改 ChatFormatter 配置无法生效。

现在 ChatFormatter 存在时保留其渲染器，继续使用 `%cctsystem_identity_no_world% &7: &f{message}`。无 ChatFormatter 时使用无样式根节点，正文默认白色。称号、会员颜色和匿名身份保留。3 项组件颜色/样式回归测试通过。

仅将本次两个类替换进线上 CCTSystem.jar，逐个 ZIP 成员比较确认其余字节未改变，避免把工作区已有的其他修改一并部署。

StrikePractice 的 ArenaFilter 明确要求 kit.isBuild 与 arena.isBuild 相同，不能仅在 Nodebuff 原竞技场增加套装白名单。为 10 张通用地图准备独立 world 副本和 `_builduhc` 竞技场，建筑标记为 true，限定普通和排位 BuildUHC；原地图注册不变。副本移除 uid.dat、session.lock 和玩家存档，核对所有 MCA 区块定位表，Multiverse 自动加载，保留箭/鱼钩等 misc 实体生成。

builduhc、builduhcelo 默认背包槽 12/13/14 分别添加钻石镐、钻石斧、3 个 Golden Head（金苹果载体，金色英文名称，关闭斜体）。使用插件原有的 Golden Head 消耗识别和效果配置。

对局计分板新增 `CPS： <cps> / <opponent_cps>` 一行，绿色为自己，红色为对手。已核对该版本原生占位符注册。无需另写 CPS 计数器。

配置与同内容 .pending 一起部署；更新原有 CCTDuelsDeployBootstrap，额外在 onLoad 应用 kits.yml、scoreboard.yml，以免旧进程停服保存覆盖新配置。

备份：`/www/cctstudio2/backup/duels-chat-builduhc-cps-20261010-114431`。
线上 CCTSystem SHA256：`adafcaa6eb5e213b40fc9e07b43df3c7351a0ad060e8761368471aa1a1d52570`。
改动的文件、JAR 和新地图目录所有者均为 www。

未重启、重载或发送任何游戏指令。需要用户重启 Duels 后实际验证管理员/普通玩家正文颜色、不同地图的 BuildUHC 匹配、Golden Head 消耗、回滚，以及两个客户端的实时 CPS 显示。
