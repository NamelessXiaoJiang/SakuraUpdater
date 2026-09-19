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

## Installation

### Server-side installation

1. Place `sakuraupdater-[version].jar` into the server's `mods` folder.
2. Start the server. Configuration files will be generated on first run.
3. Edit the server configuration file at `config/sakuraupdater-server.toml`.

### Client-side installation

1. Place `sakuraupdater-[version].jar` into the client's `mods` folder.
2. Start the game. Configuration files will be generated on first run.
3. Edit the client configuration file at `config/sakuraupdater-client.toml`.

### Server-side independent operation

1. Place `sakuraupdater-[version].jar` into a separate folder.
2. Run the server independently with `java -jar sakuraupdater-[version].jar`.
3. Edit the client configuration file at `sakuraupdater-client.toml`.

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

# Edit version description (only the description: the file list and the timestamp are left untouched)
/sakuraupdater data edit v1.0.1 "Updated version description"

# Rebuild a version's file list from the current sync directories
/sakuraupdater data repair v1.0.1

# Delete a version
/sakuraupdater data delete v1.0.1

# Clear all version data
/sakuraupdater data clear
```

> `data repair <version>` is for versions whose file list is empty — for example versions damaged by an older build's `data edit` bug, or versions that were never committed properly. It keeps the description and timestamp, so the order of your update log does not change. Note that it records the **current** contents of your sync directories, not a historical snapshot of that release.

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

## 安装步骤

### 服务器端安装

1. 将 `sakuraupdater-[version].jar` 放入服务器的 `mods` 文件夹。
2. 启动服务器，首次运行会生成配置文件。
3. 编辑 `config/sakuraupdater-server.toml` 配置文件。

### 客户端安装

1. 将 `sakuraupdater-[version].jar` 放入客户端的 `mods` 文件夹。
2. 启动游戏，首次运行会生成配置文件。
3. 编辑 `config/sakuraupdater-client.toml` 配置文件。

### 服务端独立运行

1. 将 `sakuraupdater-[version].jar` 放入单独文件夹。
2. 直接 `java -jar sakuraupdater-[version].jar` 即可运行服务端。
3. 编辑 `sakuraupdater-client.toml` 配置文件。

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

# 编辑版本描述（只改描述：文件清单与时间戳都不会被动）
/sakuraupdater data edit v1.0.1 "更新了版本描述"

# 重建某个版本的文件清单（按当前同步目录重新扫描）
/sakuraupdater data repair v1.0.1

# 删除版本
/sakuraupdater data delete v1.0.1

# 清空所有版本数据
/sakuraupdater data clear
```

> `data repair <版本>` 用于文件清单为空的版本——例如被旧版本 `data edit` 的 bug 清空过的版本，或当时没 commit 好的版本。它会保留描述与时间戳，所以不会打乱更新日志的顺序。注意：它记录的是**当前**同步目录里的内容，不是那一版当时的历史快照。

### 2. 客户端玩家操作

#### 自动检查更新

当玩家进入游戏时，如果检测到服务器有新版本，会自动弹出更新界面。
