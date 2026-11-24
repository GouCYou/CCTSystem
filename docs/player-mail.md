# 玩家邮件与网络波动补偿

输入 `/mail`，或点击 `/cct` 个人中心的「邮件」，即可在聊天栏查看未读邮件和未领取的附件。「阅读」显示正文，「领取」显示正文并领取该邮件的附件。「一键领取」或 `/mail claimall` 可领取所有有效邮件的附件，尚未打开的正文仍保留为未读。收件箱每页显示五封邮件，底部显示当前页和总页数。点击「上一页」「下一页」可翻页，也可输入 `/mail page <页码>` 跳页。收件箱为空时显示「当前没有未读或未领取的邮件」。每次进入或切换游戏子服时，有待处理邮件就会收到提醒。

基岩版玩家打开收件箱后，可使用 `/mail read <编号>` 阅读、`/mail claim <编号>` 领取单封附件、`/mail claimall` 一键领取，或用 `/mail page <页码>` 翻页。

领取资格由收件人 UUID、有效期和领取状态共同校验。神秘宝库与神秘之尘由大厅 GadgetsMenu 发放，玩家可以先领取，再进入大厅到账。生存金币由生存服经济接口发放。点券和会员走现有 CCTSystem 服务。

官网管理后台的「服务器邮件」可按玩家名或 UUID 发送纯文字邮件或带奖励的邮件，也可编辑未读的纯文字邮件和附件未领取的邮件。Owner 和 Admin 可以编辑，其他后台人员可以读取。

游戏内使用以下指令。附件的 `type` 支持 `MYSTERY_BOX`、`MYSTERY_DUST`、`POINTS`、`SURVIVAL_COINS`、`MEMBERSHIP`。会员的 `amount` 是天数，`tier` 是会员等级。当 `expiresAt` 为空时邮件长期有效，也可填写 Unix 毫秒时间戳。

```text
/cctadmin mail list
/cctadmin mail send 玩家名 {"title":"活动通知","body":"周末活动将于晚上八点开始。","expiresAt":null,"rewards":[]}
/cctadmin mail send 玩家名 {"title":"周末活动","body":"活动奖励已寄送。","expiresAt":null,"rewards":[{"type":"SURVIVAL_COINS","amount":1000}]}
/cctadmin mail edit 邮件编号 {"title":"周末活动","body":"活动奖励已更新。","expiresAt":null,"rewards":[{"type":"MEMBERSHIP","amount":14,"tier":"vip_plus"}]}
```

## 自动补偿

Velocity 上的 `plugins/cctsystem/lag-compensation.yml` 提供默认规则，初始开启 1000 生存金币。官网「卡顿补偿」可编辑同样的奖励类型和开关。后台设置优先，点击「使用配置文件」恢复读取文件。

CCTNetworkNotice 持续探测公网游戏入口。自动检测确认波动后，将满足个人受影响条件的在线玩家加入补偿。数据库按每轮波动和玩家 UUID 去重，重启时也会保留尚未确认恢复的记录。确认持续恢复后结束本轮。手动开启波动提示不会触发补偿。附件发放完成后发送绿色聊天提示，邮件保留发放记录。

外部发奖结果不明确时，附件显示「待核对」。管理员可核对金币、道具或点券记录，再补发确认未到账的附件。
