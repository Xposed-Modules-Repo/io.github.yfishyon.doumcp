# 抖M（DouMCP）

抖音 MCP Xposed 模块 —— 注入抖音进程内启动 MCP 服务，让 Claude 等 AI 客户端**直接操控一台真实登录的抖音**。

与走网页解析 / 协议模拟的抖音 MCP 不同：抖M 活在抖音自己的进程里，所有操作复用宿主的接口与 IM SDK —— 登录态、设备签名、加密全部现成，稳定跟随抖音版本运行。

## ✨ 功能特性

- **账号** —— 多账号列表 / 一键切换 / 任意用户资料（uid 或 secUid）
- **私信 IM** —— 会话列表（火花/小火人）、消息分页（全类型解析）、发文本（含引用回复）、发图（同步返回 msgId）、撤回、编辑、表情表态、输入状态、已读、未读统计、云表情目录
- **视频** —— 作品详情（视频/图文）、用户作品列表（分页）
- **评论** —— 一级评论 + 楼中楼（表情/图片/@提及）、发表评论（顶评/回复/楼中楼）
- **搜索** —— 综合搜索（聚合或按类型过滤，分页）
- **本地数据库** —— 直接 SQL 查询抖音全部 SQLite 库；**加密库自动解密**（含 IM 消息库，任意账号）
- **进程内服务** —— MCP 服务跑在抖音进程内，回环地址 + Bearer 密钥鉴权

## 🔧 工作原理

```
Claude / MCP 客户端
      │  HTTP (Streamable) + Bearer Token
      ▼
127.0.0.1:19320  ←── Ktor 服务（抖音进程内）
      │
      ├── 反射调用抖音自己的接口与 SDK（发消息/评论/搜索…）
      ├── DexKit 定位混淆代码（全等字符串锚点，结果两级缓存）
      └── 宿主 WCDB 直解加密数据库
```

## 📦 安装

1. 需要 root + [LSPosed](https://github.com/LSPosed/LSPosed)（Zygisk 版）
2. 安装抖M APK，在 LSPosed 中启用并勾选作用域：**抖音**
3. 强制停止抖音并重新打开，出现「抖M：服务已启动」提示即生效
4. 配置入口：**抖音设置页最上方「抖M设置」**（原生样式）—— 端口（默认 19320）与鉴权密钥，保存后重启抖音生效

### MCP 客户端配置

支持 Streamable HTTP 传输的客户端（Claude Code、Claude Desktop 等）：

```json
{
  "mcpServers": {
    "doumcp": {
      "type": "http",
      "url": "http://<手机IP>:19320/mcp",
      "headers": {
        "Authorization": "Bearer <你的密钥>"
      }
    }
  }
}
```

> 手机与客户端需在同一网络；未配置密钥时服务不鉴权（仅建议本机调试使用）。

## 🧰 可用工具（26 个）

| 域 | 工具 | 功能 |
|----|------|------|
| 账号 | `getUserAccounts` / `switchAccount` | 已登录账号列表 / 切换账号 |
| 账号 | `getUserProfile` | 任意用户完整资料（uid/secUid） |
| 视频 | `getAwemeDetail` | 作品详情（视频/图文，含统计与地址） |
| 视频 | `getUserAwemes` | 用户作品列表（分页） |
| 搜索 | `search` | 综合搜索（聚合或 type 过滤） |
| 评论 | `getComments` / `getCommentReplies` | 一级评论 / 楼中楼（含表情、图片、@提及） |
| 评论 | `postComment` | 发表评论（顶评/回复/楼中楼/@提及，云表情 `[表情名]` 语法） |
| IM | `getConversations` | 会话列表（未读/最后消息/火花状态） |
| IM | `getMessages` | 消息分页（文本/图片/卡片全类型，含引用关系） |
| IM | `sendTextMessage` | 发文本（支持 quoteMessageId 引用回复） |
| IM | `sendImageMessage` | 发本地图片（同步等待，返回 msgId） |
| IM | `revokeMessage` / `editMessage` | 撤回 / 编辑自己发的消息 |
| IM | `setMessageReaction` | 消息表情表态（多值，add/unset） |
| IM | `sendTypingStatus` | 输入中状态（可定时自动停止） |
| IM | `markConversationRead` / `getUnreadCount` | 已读 / 全局未读统计 |
| IM | `getEmojis` | 云表情目录（可搜、含限时活动表情） |
| 系统 | `getCurrentTime` | 手机当前时间（iso/ms/秒/自定义格式） |
| 数据库 | `listDatabases` / `listTables` | 枚举库 / 表结构 |
| 数据库 | `queryDatabase` | 只读 SQL（扁平 KV 输出） |
| 数据库 | `executeDatabaseStatement` | 任意 SQL 写操作（后果自负） |
| 调试 | `ping` | 连通性检查 |

### 数据库直查示例

IM 消息库是 WCDB 加密的，抖M 直接解密（密钥由账号 uid 推导，任意账号的库都能开）：

```
queryDatabase: {"database":"encrypted_<uid>_im.db",
                "sql":"SELECT msg_server_id, conversation_id, created_time FROM msg ORDER BY created_time DESC LIMIT 3"}
→ 3 row(s):
  msg_server_id=76920xxxxxxxxxxxxxx, conversation_id=0:1:<对方uid>:<你的uid>, created_time=1790936562351
```

> IM 库的时间戳是毫秒（13 位）；个别扩展字段是秒（10 位），换算前先看位数。

## 🏗️ 构建

```bash
./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 发布包
```

- `versionName` = 当前提交的短 git 哈希；`versionCode` 在 `app/build.gradle.kts` 手动递增
- 版本号参与 DexKit 定位缓存键（模块或抖音任一升级，缓存自动失效重扫）

### 项目结构

```
doumcp/
  ModuleMain.kt / McpServerHost.kt / ModulePrefs.kt / SettingsInjector.kt
  core/      HostRuntime（宿主上下文/classLoader 单例）、DexKitSupport（定位+缓存）、
             Reflect、ModLog、DouToastHelper、ResolvedCache(FastKV)
  features/  按功能分包，每包 tool / bridge / resolver 三层
    account/ video/ comment/ search/ im/ system/ database/
```

## ⚠️ 已知限制

- 发送语音（silk 编码转码链路复杂）与发布作品（上传凭证+大量混淆字段）暂未实现
- 评论区风控：短时间连发评论可能被平台静默过滤（请求成功但不出现在列表）
- 需登录的功能未登录时返回错误；数据库写操作请自行承担后果

## 📄 免责声明

仅供学习与研究使用。使用者需遵守相关法律法规及抖音用户协议；作者不对使用本模块产生的任何损失或账号风险承担责任。
