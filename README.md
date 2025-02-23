
# AnGeCoordinate GUI坐标管理插件

> 这是一个为黯阁我的世界服务器开发的GUI坐标管理插件，提供了一个直观的图形界面来帮助玩家管理和使用坐标点。

## 🎯 功能特性

- 完全GUI操作界面
- 保存当前位置为坐标点
- 支持坐标点备注名称
- 支持传送到已保存的坐标
- 传送冷却时间限制
- 传送前倒计时
- 权限管理系统
- 支持蹲下+F键快速打开菜单（可配置）

## 🛠️ 技术栈

- Java
- Spigot API
- ProtocolLib（必需依赖）
- Maven

## 📦 安装说明

1. 确保服务器已安装ProtocolLib插件
2. 下载插件jar文件：
   - [点击下载最新版本](target/angecoordinate-1.0-SNAPSHOT.jar)
   - 或从源码编译：`mvn clean package`
3. 将插件jar文件放入服务器的plugins文件夹
4. 重启服务器或使用插件重载命令

## 🎮 使用方法

基础命令：
```
/an - 打开坐标管理GUI界面
/a reload - 重载插件配置（需要管理员权限）
```

GUI操作：
- 点击绿宝石添加新坐标
- 点击纸张查看坐标详情
- 点击红石屏障关闭菜单

## 🔧 配置文件

配置文件位于 `plugins/AnGeCoordinate/config.yml`

主要配置项：
```yaml
teleport-wait-time: 3 # 传送等待时间（秒）
enable-teleport-wait: true # 是否启用传送等待
max-teleports-per-5min: 2 # 5分钟内最大传送次数
admin-bypass-limit: true # 管理员是否绕过限制
enable-sneak-f-key: false # 是否启用蹲下+F键快捷打开菜单
```

## 🔐 权限节点

- `angecoordinate.use` - 允许使用基本功能（默认所有玩家）
- `angecoordinate.admin` - 允许使用管理命令（默认OP）

## 📄 开源协议

本项目采用 MIT 协议开源。

## 👥 贡献者

- @开发 小夜HEYP
- @服主 黯泽Anze 
