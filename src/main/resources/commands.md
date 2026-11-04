# BetaAchievement 指令说明

插件使用 `/achievement` 主指令，并保留简短别名 `/ach`。

## 玩家指令

| 指令 | 作用 |
|------|------|
| `/ach` | 查看自己的当前轮成就进度 |
| `/ach run info` | 查看当前轮次、累计时间和全部单成就耗时 |
| `/ach run next` | 完成过全成就后创建下一轮 |
| `/ach run abandon` | 放弃第 2 轮及以后的当前活动轮次 |
| `/ach history` | 查看最近 10 轮成绩 |
| `/ach pb` | 查看自己的最快成绩 |
| `/ach top [数量]` | 查看全服最快成绩 |

查看其他玩家的信息需要 `betaachievement.view.others`，其余玩家指令不需要额外权限。旧写法 `/ach run history` 仍然兼容。

## 管理员指令

| 指令 | 作用 | 所需权限 |
|------|------|----------|
| `/ach check <玩家名>` | 查看其他在线玩家的成就进度 | `betaachievement.view.others` |
| `/ach pb <玩家名>` | 查看指定在线玩家的最快成绩 | `betaachievement.view.others` |
| `/ach grant <玩家名> <成就ID>` | 手动解锁单个成就并补齐前置 | `betaachievement.grant` |
| `/ach grantall <玩家名>` | 解锁全部 16 个成就 | `betaachievement.grantall` |
| `/ach revoke <玩家名> <成就ID>` | 撤销玩家的单个成就 | `betaachievement.revoke` |
| `/ach reset <玩家名>` | 作废旧轮次并创建新轮次 | `betaachievement.reset` |
| `/ach debug <成就ID>` | 进入调试模式 | `betaachievement.debug` |
| `/ach debug` | 退出调试模式 | `betaachievement.debug` |

> 说明：
> - `/ach` 对应 `/achievement`，两者完全等价。
> - 目标玩家必须在线。
> - `grant` 会自动补全缺失的前置成就；`grantall` 一次性解锁全部 16 个成就。
> - 调试模式仅对执行指令的管理员本人生效：忽略前置成就要求，且触发成就时只发调试信息、不写入数据库；再次输入 `/ach debug` 退出。
> - 玩家成就数据仍在加载时，`grant`、`grantall`、`revoke` 和 `reset` 会排队到加载完成后按顺序执行。
> - 玩家获得本轮第一个成就时开始计时；完成 16 个成就后停止计时。
> - 开启新轮次不会删除任何历史轮次或单成就时间。
> - 必须先完成过一次 16/16，才能使用 `/ach run next` 和 `/ach run abandon`。
> - `READY` 轮次不能放弃；第 2 轮及以后的活动轮次超过 12 小时未完成时，会自动删除该轮数据。
> - 管理员在当前轮授予、批量授予或撤销成就时，该轮会标记为 `INVALID`，不参与排行榜。

## 用法示例

```
/ach run info                          # 查看当前轮次和成就耗时
/ach run next                          # 完成全成就后开始下一轮
/ach history                           # 查看历史轮次
/ach pb                                # 查看个人最佳成绩
/ach top 10                            # 查看全服前 10 名
/ach grant Steve mineWood              # 为 Steve 解锁「获得木头」
/ach grantall Steve                    # 为 Steve 解锁全部成就
/ach revoke Steve buildFurnace         # 撤销 Steve 的「“热”门话题」
/ach reset Steve                       # 作废当前轮并创建新轮
/ach debug killEnemy                   # 开始调试「怪物猎人」的触发过程
/ach debug                             # 退出调试模式
```

## 成就 ID 对照

| 成就 ID | 中文名 |
|---------|--------|
| `openInventory` | 打开物品栏 |
| `mineWood` | 获得木头 |
| `buildWorkBench` | 这是？工作台！ |
| `buildPickaxe` | 采矿时间到！ |
| `buildFurnace` | “热”门话题 |
| `acquireIron` | 来硬的 |
| `buildHoe` | 耕种时间到！ |
| `makeBread` | 烤面包 |
| `bakeCake` | 蛋糕是个谎言 |
| `buildBetterPickaxe` | 获得升级 |
| `cookFish` | 美味的鱼儿 |
| `onARail` | 在铁路上 |
| `buildSword` | 出击时间到！ |
| `killEnemy` | 怪物猎人 |
| `killCow` | 斗牛士 |
| `flyPig` | 当猪飞的时候 |

## 权限列表

| 权限 | 默认 | 说明 |
|------|------|------|
| `betaachievement.admin` | op | 管理员，拥有以下全部权限 |
| `betaachievement.view.others` | op | 查看他人成就 |
| `betaachievement.grant` | op | 解锁单个成就 |
| `betaachievement.grantall` | op | 解锁全部成就 |
| `betaachievement.revoke` | op | 撤销单个成就 |
| `betaachievement.reset` | op | 清空全部成就 |
| `betaachievement.debug` | op | 调试模式 |
