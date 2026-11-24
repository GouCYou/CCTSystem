# Minecraft Java 正版验证与奖励

所有玩家继续使用原来的服务器地址。玩家先通过 AuthMe 注册或密码登录，再输入 `/premium`，也可以从支持原生对话的暂停菜单导航点击「绑定 Java 正版账号」。从其他游戏子服发起时，Velocity 会先连接大厅，成功进入后自动继续。玩家在三分钟内用正版 Java 客户端重连同一地址，Velocity 验证 Mojang 会话，AuthMe 再通过原密码确认现有账号的归属。完成后，系统绑定 Mojang UUID、现有离线 UUID 和 AuthMe 注册记录。

已绑定玩家在导航中可选择「解绑 Java 正版账号」，也可输入 `/premium unbind`，然后点击确认。解绑后下次登录使用原来的 AuthMe 密码。相同账号重新绑定仍需走正版会话和密码流程，首次奖励记录会保留。

已绑定账号使用正版客户端连接时自动发起验证。客户端报告的 UUID 只用于决定是否发起验证，免输密码和首领奖励都需要真正通过 Mojang 会话认证。通常的离线启动器仍走原来的密码登录。兼容性异常时，管理员可以在 Velocity 控制台运行 `premium password <玩家名>` 开放 10 分钟密码登录。使用 AuthMe 动态口令的账号继续输入密码和动态口令。QQBotAuth 继续负责社交绑定和放行。

游戏身份沿用由原游戏名称计算的离线 UUID。背包、末影箱、经验、经济、权限、领地等仍按现有离线 UUID 读取。Mojang UUID 用于识别正版账号和首领去重。正版玩家改名、昵称大小写与旧注册不一致、或原 AuthMe 账号被删除重建时，自动登录不会迁移数据或覆盖绑定，请先核对原游戏名和账号归属。

普通入口继续支持 AuthMe 注册和密码登录。为防止利用同出口 IP 复用正版免密登录产生的会话，已绑定账号在普通入口通过密码登录，未参与绑定的账号仍按现有 AuthMe 会话设置运行。首次正版绑定也要求当次通过密码验证，AuthMe 的 IP 恢复会话不能替代这一步。Mojang 会话服务异常或验证失败时，该次正版验证会失败，玩家可用离线模式或临时密码登录。

## 安装

1. 运行 `./gradlew test :cctsystem-distribution:shadowJar`，将 `cctsystem-distribution/build/libs/CCTSystem-1.0.0.jar` 替换到 Velocity 和已适配的 Paper 实例。保留旧 JAR 备份。
2. 从 JAR 或实例目录打开 `premium-login.yml`。Velocity 位于 `plugins/cctsystem/`，Login2 和 Lobby2 位于 `plugins/CCTSystem/`。三份配置使用同一个独立密钥，密钥可用 `openssl rand -hex 32` 生成。限制配置文件的读取权限，将三份的 `enabled` 设为 `true`。
3. Velocity 的 CCTSystem `config.yml` 启用共享数据库，使用与 Login2 相同的 CCTSystem MySQL。Velocity 保持 `online-mode = false` 和 `player-info-forwarding-mode = "modern"`。Login2 的 AuthMe 保持原生 `settings.enablePremium` 和 `settings.registration.dialog.preJoin.enable` 关闭，继续使用入服后对话。
4. `login-server` 指向 Velocity 的登录服。当前大厅同时显示标题并发放奖励，`title-server` 和 `reward-server` 填 `lobby`。奖励服需要现有的 `business-authority` 和 `points-authority` 角色。
5. 将更新后的导航插件装到 Velocity。原生暂停菜单的「绑定 Java 正版账号」执行 `/premium`。原版「服务器链接」沿用现有内容。旧客户端可直接输入 `/premium`。
6. 由管理员通过 MCSManager 先重启 Login2、Lobby2，再重启 Velocity。其他 Paper 实例按维护计划正常重启。新记录由 CCTSystem 的自动数据库迁移创建。

如需关闭功能，将上述三份独立配置的 `enabled` 设为 `false`，再按相同顺序重启。领取记录留在数据库中，重新启用后仍按原记录去重。

## 社交绑定

正版赠送的会员会等待玩家绑定 QQ 或 Discord，等待期间不开始计算会员时长。点券按原奖励配置发放。`require-social-binding: true` 可要求玩家先完成社交绑定再绑定 Java 正版账号。Velocity 和 Login2 的配置应一致。默认允许先绑定正版账号。

## 配置

| 字段 | 默认值与用途 |
| --- | --- |
| `signing-key` | Velocity、Login2、Lobby2 共用的 64 位十六进制密钥 |
| `titles.title`、`first-subtitle`、`returning-subtitle`、`pending-subtitle` | 屏幕中央标题和对应状态的副标题，支持 `&` 颜色代码 |
| `titles.enabled`、`show-returning` | 控制是否显示标题、是否显示回访欢迎 |
| `titles.fade-in-ticks`、`stay-ticks`、`fade-out-ticks` | 默认 10、65、15 tick。20 tick 约为一秒 |
| `rewards.membership-tier`、`membership-days` | 默认 `vip_plus`、14 天。天数设为 0 可关闭会员奖励 |
| `rewards.points` | 默认 50 点券。设为 0 可关闭点券奖励 |
| `rewards.commands` | 默认空。可填写奖励服控制台命令，无需斜杠，支持 `{player}` 和 `{uuid}`。命令需要能对离线目标执行 |

绑定时保存当时的奖励配置。较高等级的会员继续使用，低等级赠送时长进入暂停队列。同级奖励累加到现有时长。Title 在大厅读取发放状态后显示，尚在处理时使用待处理副标题。

会员发放和完成记录在同一个事务提交。PlayerPoints 使用 CCTSystem 点券流水中固定的操作 ID。第三方命令执行前会先登记，崩溃或返回失败时保留状态供管理员核对，防止把部分已执行的命令再跑一遍。若日志显示 `requires review`，可只读查看 `cct_java_login_rewards` 的三项发放状态，按日志中的操作 ID 对照 CCTSystem 点券记录，再通过正常管理功能补发确认未发出的部分。

## 测试

1. 使用已有离线账号从原入口登录，确认注册、会话登录、QQ 放行及已有背包、经济、会员、领地等照常。
2. 用一个已有 AuthMe 账号，从其他游戏子服执行 `/premium`，确认会切到大厅并自动显示绑定提示，再通过正版启动器重连原地址。先输一次错误密码，确认没有绑定和奖励。再用正确密码完成登录，检查大厅 Title、50 点券和 14 天 VIP+。
3. 用新测试名注册，或为测试账号预置更高会员后执行第 2 步。检查 VIP+ 的 14 天处于暂停状态。
4. 断线重连、跨服并在测试环境重启后再登录。确认离线 UUID 相同，已绑定且无 AuthMe 动态口令的账号免输密码，点券和会员只发一次。已启用动态口令的账号继续输入密码和动态口令。
5. 用相同网络出口上的离线客户端访问普通入口并使用已绑定名称，确认仍要求密码。验证遇到问题时，在 Velocity 控制台对测试账号开放临时密码登录后重试。核对其他离线账号仍可照常登录。
6. 测试 Java 聊天和常用命令。Mojang 签发的聊天公钥绑定正版 UUID，部分客户端版本或要求签名的聊天插件可能与离线 UUID 存在兼容性差异。

## 源码参考

审阅了三个 MIT 项目。FastLogin 展示了 Velocity 的在线模式握手和 UUID 映射，FastLoginPlus 展示了 AuthMe 配合与离线 UUID 兼容，VeloAuth 展示了同名冲突和身份映射策略。实现直接调用 Velocity 与 AuthMe API，没有引入这些项目的 JAR 或复制其源码。

- [FastLogin 9486138](https://github.com/TuxCoding/FastLogin/tree/9486138)
- [FastLoginPlus fb5a17a](https://github.com/Hayston1001/FastLoginPlus/tree/fb5a17a)
- [VeloAuth bffbce6](https://github.com/rafalohaki/VeloAuth/tree/bffbce6)
