<div align="center">
<img src="img/sakuraupdater.png" width="128" alt="SakuraUpdater">
<h1>SakuraUpdater</h1>
</div>

*([中文版请向下滚动 | Scroll down for Chinese version](#sakuraupdater-中文))*
## Introduction


SakuraUpdater is a NeoForge Minecraft mod that enables automatic synchronization of mod files between a server and clients, allowing players to receive server updates automatically, similar to other games.

<div align="center">
<img src="img/image1.png" width="500" alt="SakuraUpdater">
</div>

## Features

- [x] 🔄 Automatic server update checks
- [x] 🕒 Display update logs in the update UI
- [x] 📁 Multiple sync modes supported (mirror, push, ignore)
- [x] 🎮 Graphical update UI
- [x] ⚙️ Configurable client and server settings
- [x] 🚀 The server can run independently
- [x] 📦 Separate client / server / standalone jars (the client jar is only ~0.5 MB)

## Which file should I download?

Every release ships three jars — pick the one that matches your role:

| File | Size | Use it for | Bundles SQLite? |
| --- | --- | --- | --- |
| `sakuraupdater-<version>-<mc>-client.jar` | ~0.5 MB | Players' clients | No |
| `sakuraupdater-<version>-<mc>-server.jar` | ~14 MB | Dedicated servers (it also contains everything a client needs) | Yes |
| `sakuraupdater-<version>-<mc>-standalone.jar` | ~14 MB | Running the updater standalone with `java -jar` | Yes, bundled inside |

The `-client` jar is the slim one: a client never touches the update database, so it does not need SQLite.
**Do not put `-client` on a dedicated server** — the server needs SQLite and will log an error telling you to use `-server` instead.

## Installation

### Server-side installation

1. Place `sakuraupdater-[version]-server.jar` into the server's `mods` folder.
2. Start the server. Configuration files will be generated on first run.
3. Edit the server configuration file at `config/sakuraupdater-server.toml`.

### Client-side installation

1. Place `sakuraupdater-[version]-client.jar` into the client's `mods` folder.
2. Start the game. Configuration files will be generated on first run.
3. Edit the client configuration file at `config/sakuraupdater-client.toml`.

### Server-side independent operation

1. Place `sakuraupdater-[version]-standalone.jar` into a separate folder.
2. Run the server independently with `java -jar sakuraupdater-[version]-standalone.jar`.
3. Edit the client configuration file at `sakuraupdater-client.toml`.

> The standalone jar carries its own dependencies (Gson, NightConfig, SLF4J, SQLite) under `standaloneLibs/` and unpacks them into a `lib/` folder next to the jar on first run. The `-client` and `-server` jars cannot be started this way.

### Upgrading from older versions

Older releases shipped everything as a single `sakuraupdater-[version].jar`. To upgrade:

1. Delete the old jar and put the `-client` / `-server` jar for your role in its place.
2. If that jar is also the one your server pushes to players, keep the `-client` jar in the folder your `SYNC_DIR` reads from (see the tips under Configuration) and run `/sakuraupdater commit <version> <description>` once more, so the file list points at the new file name. In `mirror` mode the old file is then removed from clients automatically.

## Configuration

### Server configuration (`sakuraupdater-server.toml`)

```toml
# File server port
port = 25564

# Sync directory configuration, format: ["target_dir:mode:source_dir:extra_source_dir:..."]
# Mode explanations:
# - mirror: mirror mode, fully synchronize source directory to the target directory
# - push: push mode, push server files to clients
# - pull: pull mode, pull files from clients to the server
# - ignore: ignore mode, ignore files matching the regex. Since the path is included, ".*filename$" is recommended.
SYNC_DIR = [
    "mods:mirror",                           # synchronize the mods folder
    "config:push:clientconfig:config",       # push config and clientconfig to the client
    "resourcepacks:mirror",                  # synchronize resourcepacks folder
    "mods:ignore:.*abc\.jar$"                # ignore files in mods ending with 'abc.jar'
]
```

> **Mind the updater jar itself.** Everything in the sync *source* is pushed to every client — including the updater jar. Since the client jar is much smaller than the server one, keep them apart:
>
> ```toml
> # Recommended: the server keeps its own -server.jar in mods/,
> # and players receive the slim -client.jar from a separate folder.
> SYNC_DIR = [
>     "mods:mirror:clientmods",   # sync clientmods/ (holds the -client jar) to the client's mods/
>     "config:push:clientconfig:config"
> ]
> ```
>
> Or, if you prefer a single folder, exclude the updater by name and let each side keep its own jar:
>
> ```toml
> SYNC_DIR = [
>     "mods:mirror",
>     "mods:ignore:.*sakuraupdater.*\.jar$"
> ]
> ```

### Client configuration (`sakuraupdater-client.toml`)

```toml
# Server host address
host = "localhost"

# Server port (should match the sakuraupdater-server.toml configuration, especially if using frp or other proxies)
port = 25564

# Current client version (managed automatically; do not edit manually)
now_version = ""
```

## Usage

### 1. Server administrator operations

> ### When using the independent server, remove the /sakuraupdater command prefix and use the commit and data commands directly

#### Create a release/commit

`/sakuraupdater commit <version> <description-or-path-to-description-file>`

**Example:**
`/sakuraupdater commit v1.0.1 Fixed item duplication bug\nAdded new enchantments`

**Or using a text file (minimal markdown supported):**
`/sakuraupdater commit v1.0.1 description.md`

#### Manage data versions

```shell
# List all versions
/sakuraupdater data list

# Show details for a specific version
/sakuraupdater data show v1.0.1

# Edit version description
/sakuraupdater data edit v1.0.1 "Updated version description"

# Delete a version
/sakuraupdater data delete v1.0.1

# Clear all version data
/sakuraupdater data clear
```

### 2. Client player operations

#### Automatic update check

When a player joins the game, the client will automatically check whether the server has a newer version and will open the update UI if an update is available.

---

# SakuraUpdater (中文)

## 简介

SakuraUpdater 是一个 Minecraft NeoForge 模组，用于自动更新服务器的 mod 文件，让玩家能够像其他游戏一样自动获取服务器更新。

<div align="center">
<img src="img/image2.png" width="500" alt="SakuraUpdater">
</div>

## 功能特性

- [x] 🔄 自动检查服务器更新
- [x] 🕒 在更新界面显示更新日志
- [x] 📁 支持多种文件同步模式（mirror、push、ignore）
- [x] 🎮 图形化更新界面
- [x] ⚙️ 可配置的客户端和服务器设置
- [x] 🚀 服务端可独立运行
- [x] 📦 客户端 / 服务端 / 独立模式分包发布（客户端包仅 ~0.5 MB）

## 该下载哪个包？

每个 Release 会发三个包，按角色选一个：

| 文件 | 大小 | 用途 | 是否自带 SQLite |
| --- | --- | --- | --- |
| `sakuraupdater-<版本>-<MC版本>-client.jar` | ~0.5 MB | 玩家客户端 | 不含 |
| `sakuraupdater-<版本>-<MC版本>-server.jar` | ~14 MB | 专用服务端（也包含客户端所需的全部内容） | 含 |
| `sakuraupdater-<版本>-<MC版本>-standalone.jar` | ~14 MB | `java -jar` 独立运行 | 内含，无需额外安装 |

`-client` 是瘦包：客户端进程从不访问更新数据库，所以不需要 SQLite。
**不要把 `-client` 放到专用服务端**——服务端需要 SQLite，启动时会打日志提示你改用 `-server`。

## 安装步骤

### 服务器端安装

1. 将 `sakuraupdater-[version]-server.jar` 放入服务器的 `mods` 文件夹。
2. 启动服务器，首次运行会生成配置文件。
3. 编辑 `config/sakuraupdater-server.toml` 配置文件。

### 客户端安装

1. 将 `sakuraupdater-[version]-client.jar` 放入客户端的 `mods` 文件夹。
2. 启动游戏，首次运行会生成配置文件。
3. 编辑 `config/sakuraupdater-client.toml` 配置文件。

### 服务端独立运行

1. 将 `sakuraupdater-[version]-standalone.jar` 放入单独文件夹。
2. 直接 `java -jar sakuraupdater-[version]-standalone.jar` 即可运行服务端。
3. 编辑 `sakuraupdater-client.toml` 配置文件。

> 独立模式包自带依赖（Gson、NightConfig、SLF4J、SQLite），放在包内 `standaloneLibs/` 目录，首次运行会解压到 jar 旁边的 `lib/` 文件夹。`-client` 和 `-server` 包不能用这种方式启动。

### 从旧版升级

旧版本所有功能都打在同一个 `sakuraupdater-[version].jar` 里。升级步骤：

1. 删掉旧包，换成对应角色的 `-client` / `-server` 包。
2. 如果这个包同时是要推给玩家的那个，把 `-client` 包放在 `SYNC_DIR` 读取的目录里（见下方配置说明），并重新执行一次 `/sakuraupdater commit <版本号> <描述>`，让文件清单指向新文件名。`mirror` 模式下旧文件名会自动从客户端删除。

## 配置说明

### 服务器配置 (`sakuraupdater-server.toml`)

```toml
# 文件服务器端口
port = 25564

# 同步目录配置，格式: ["目标目录:模式:源目录:额外的源目录:..."]
# 模式说明:
# - mirror: 镜像模式，完全同步源目录到目标目录
# - push: 推送模式，将服务器文件推送到客户端
# - pull: 拉取模式，从客户端拉取文件到服务器
# - ignore: 忽略模式，使用正则匹配忽略文件。匹配时前面带路径，推荐用 ".*文件名$" 格式。
SYNC_DIR = [
    "mods:mirror",                           # 同步 mods 文件夹
    "config:push:clientconfig:config",       # 将 config 和 clientconfig 推送到客户端
    "resourcepacks:mirror",                  # 同步资源包文件夹
    "mods:ignore:.*abc\.jar$"                # 忽略以 abc.jar 结尾的文件
]
```

> **注意更新器自己这个包。** 同步**源**目录里的所有东西都会被推给每个客户端——包括更新器 jar。客户端包比服务端包小得多，建议把两者分开：
>
> ```toml
> # 推荐：服务端自己的 mods/ 里放 -server.jar，
> # 玩家从单独的目录拿瘦包 -client.jar。
> SYNC_DIR = [
>     "mods:mirror:clientmods",   # 把 clientmods/（放 -client 包）同步到客户端的 mods/
>     "config:push:clientconfig:config"
> ]
> ```
>
> 或者仍然只用一个目录，但把更新器排除掉，让两侧各留自己的包：
>
> ```toml
> SYNC_DIR = [
>     "mods:mirror",
>     "mods:ignore:.*sakuraupdater.*\.jar$"
> ]
> ```

### 客户端配置 (`sakuraupdater-client.toml`)

```toml
# 服务器主机地址
host = "localhost"

# 服务器端口（需与sakuraupdater-server.toml配置一致，如使用frp等代理后的端口）
port = 25564

# 当前客户端版本（自动管理，请勿手动修改）
now_version = ""
```

## 使用方法

### 1. 服务器管理员操作

> ### 在使用独立服务端时，需去除 /sakuraupdater 命令前缀，直接使用 commit、data 命令

#### 创建更新版本

`/sakuraupdater commit <版本号> <描述信息/描述文件路径>`

**例如:**
`/sakuraupdater commit v1.0.1 修复了物品复制bug\n添加了新的附魔`

**或使用文本文件(极少的md格式支持):**
`/sakuraupdater commit v1.0.1 description.md`

#### 管理数据版本

```shell
# 查看所有版本
/sakuraupdater data list

# 查看特定版本详情
/sakuraupdater data show v1.0.1

# 编辑版本描述
/sakuraupdater data edit v1.0.1 "更新了版本描述"

# 删除版本
/sakuraupdater data delete v1.0.1

# 清空所有版本数据
/sakuraupdater data clear
```

### 2. 客户端玩家操作

#### 自动检查更新

当玩家进入游戏时，如果检测到服务器有新版本，会自动弹出更新界面。
