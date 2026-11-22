# myDeepSeek v1.3.1（热修复）

修复从旧版覆盖升级后**一打开就闪退**的问题。versionCode 7。

## 修复了什么

从 v1.2.x 覆盖安装 v1.3.0 的用户，冷启动时会崩溃退出，进不了任何界面。

根因是数据库迁移：v1.2.x 自己的数据库版本号也叫 10，但那一版的 9→10 加的是
`conversations.hidden`；v1.3.0 合入上游后，9→10 变成了加 `messages.tokens_cache_hit`。
老用户从 version 10 升级时，9→10 这条不会再跑，于是库里始终没有
`tokens_cache_hit` 这一列 —— 而 v1.3.0 的 `MessageEntity` 期望它有。
Room 校验 schema 时发现缺列，直接抛异常，表现就是"点开即闪退"。

修复：在 version-10 老库必经的 13→14 迁移里补上 `tokens_cache_hit`（带存在守卫，
重复执行不会报 duplicate column）。图标、应用内图标、UI 风格均未改动。

## 升级须知

- 直接覆盖安装即可，不需要卸载旧版、不会丢数据。
- 数据库仍是 version 14；本次只补齐迁移路径。
