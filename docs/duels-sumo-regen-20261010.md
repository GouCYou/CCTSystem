# Duels Sumo 与回血调整（2026-10-10）

状态：源码构建与离线验证通过；已部署，未重载、重启或执行游戏指令。需要用户重启 Duels，随后首次加载生成平台并进行真实对局验收。

## 原因和变化

- 四种 Sumo 套装原先可选择全部普通非建筑竞技场；高等级抗性使常规攻击无法致死，而平地没有掉落出口。
- 新增 CCTSumoArena 世界，使用我们自己的 CCTDuelsDeployBootstrap:sumo 生成器，四个互相隔开的半径 7 格圆形平台，平台顶面 y=2、玩家出生 y=3，下方为空。只向 sumo、sumoelo、sumobestof3、sumobestof3elo 开放；原普通竞技场排除这四种套装。
- 保留 StrikePractice 原生 insta-void=true、阈值 y=0、同步检查。插件已有对 sumo 名称的专门处理：落到 y<0 触发死亡；多回合模式走原生回合结算。其检查周期为 10 tick，因此不是离开边缘的同一瞬间判负。
- OldCombatMechanics 已全世界默认 old 模式，old-player-regen 已启用，interval=3990 毫秒、amount=1 半心、exhaustion=3，无需额外改动。
- 13 个战斗世界仍设置 peaceful，包括十个 BuildUHC 地图和 CCTGardenDuel、CCTSkyWars、CCTSpleefArena。和平难度额外回血不属于普通饱食自然回血；改为 hard，保留怪物禁止生成配置。
- Multiverse auto-heal 开关处理 EntityRegainHealthEvent.RegainReason.REGEN；保留 true。未更改治疗药水、金苹果或 Golden Head 的效果。

## 文件与备份

- /www/cctstudio2/Duels/plugins/CCTDuelsDeployBootstrap.jar
- /www/cctstudio2/Duels/plugins/CCTDuelsDeployBootstrap/config.yml：平台中心、半径、高度、材质可配置；调整位置或高度时须同步 arenas.yml，已生成区块不会因配置变化重建。
- /www/cctstudio2/Duels/plugins/StrikePractice/arenas.yml
- /www/cctstudio2/Duels/plugins/StrikePractice/config.yml
- /www/cctstudio2/Duels/plugins/Multiverse-Core/worlds.yml
- /www/cctstudio2/Duels/CCTSumoArena/level.dat：仅复制空岛模板世界元数据，未复制地形、实体或玩家文件；新区块由专用生成器生成。
- 配置同时写入 .pending，启动前由自有引导插件应用，防止旧进程停机保存覆盖。
- 备份 /www/cctstudio2/backup/duels-sumo-regen-20261010-123058。

未修改 StrikePractice、Multiverse-Core、OldCombatMechanics 等第三方 JAR。部署文件所有者 www。

## 验证与待验收

构建命令：CCTSystem/gradlew -p operations/duels-bootstrap build --offline。离线验证执行实际 Java 生成器并记录区块写入：四个岛共 596 方块，均只在 y=2，出生点有方块、边缘以外为空、原版地形及结构生成关闭。部署脚本验证所有套装仍有可用竞技场，四种 Sumo 仅匹配新增四图，其他套装不匹配 Sumo，原有竞技场其余字段未改。

重启后待验证：世界加载成功且只有四个平台；普通 Sumo 掉落结算、多回合 Sumo 进入下一回合；Nodebuff 满饥饿自然回血约四秒半颗心；BuildUHC 禁止自然回血且 Golden Head 正常；原有建筑地图恢复正常且不生成怪物。未进行线上玩法验证，不把构建或配置验证视为真实对局验收。

生成器根据 [Paper 1.21.11 官方 ChunkGenerator API](https://jd.papermc.io/paper/1.21.11/org/bukkit/generator/ChunkGenerator.html) 实现。

## 13:13 后续修正

13:01 用户重启后的日志确认 Sumo 世界加载失败，原因 WORLD_FOLDER_INVALID：此前只有 level.dat，不满足 Multiverse 至少两个世界目录结构项目的校验。已补齐 data、region、entities、poi 并设置 www 所有者。此前离线平台测试没有覆盖 Multiverse 启动校验；未进行第二次启动验证，不宣称已完成真实对局验收。

两份 BuildUHC 默认套装快捷栏改为剑、钓竿、弓、金苹果、Golden Head、钻石镐、圆石、水桶、岩浆桶；使用完整排列保留所有物品数量、附魔和属性。已保存个人编辑布局的玩家不在本次修改范围。

用户确认 Spleef、SkyWars 保留专属地图。新增十图向 builduhcelo、builduhc、premadecustomkit、sg 共享。StrikePractice ArenaManager 先过滤可用竞技场，再通过 Kotlin randomOrNull 随机选择；指定地图会按选择过滤，空闲不足会影响候选。

1.21.9+ 日志为 INFO：出生区常驻内存功能已移除。已将 Multiverse 所有世界 keep-spawn-in-memory=false，减少该提示；保留 StrikePractice 有效的竞技场区块加载设置。

KnockbackMaster 保持[作者公开基准](https://gist.github.com/xDefcon/cf649567165734ab54795ab49d33ba6e)：normal 水平0.4/垂直0.36，sprinting standard 水平0.8/垂直0.42，air 水平0.6/垂直0.8。原先就是这些数值，不能把它描述成新推荐预设。关闭 randomizer（原水平最多4%、垂直最多2%的波动）。OCM old-player-knockback 从 old 模式移动到 disabled_modules，避免两套近战速度逻辑同时开启；保留其他 OCM 模块及默认药水规则。此举是基于当前双重接管配置作出的调整，实际击退优先级及手感仍须重启后对打验证。

更新自有引导插件以支持 KnockbackMaster、OldCombatMechanics 的 pending 配置并确保先于二者加载。所有修改已部署且静态校验通过，未执行重启、重载或游戏指令。后续备份：/www/cctstudio2/backup/duels-sumo-build-kb-20261010-131304。
