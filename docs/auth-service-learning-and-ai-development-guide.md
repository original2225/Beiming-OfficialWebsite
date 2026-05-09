# auth-service 学习与 AI 协作开发指南

这份文档给下一次单独开发 `auth-service` 用。目标不是一次学完所有后端知识，而是围绕北冥官网第一个真实微服务，把需要的知识按开发顺序补齐。

## 推荐先做 auth-service

第一个正式业务模块建议从 `auth-service` 开始。

原因很简单：后面的入服审核、考试、白名单、素材投稿、通知、后台管理，都需要先知道当前用户是谁、有没有登录、是什么权限。`auth-service` 就是整个官网的身份入口。它先跑通，后面的服务才有基础。

用 Java 后端语境讲，请求进来后会先经过 `gateway-service`，再转到 `auth-service`。`auth-service` 负责处理注册、登录、邀请码、JWT、权限等级和 Minecraft ID 绑定。处理完后返回统一的 `ApiResponse`。

第一阶段不要贪多，只做最小闭环：邀请码注册、登录、返回 JWT、查询当前用户、权限注解能拦住接口。

## 当前 auth-service 状态

当前已有服务骨架、启动类、配置文件、MyBatis-Plus 配置、ping 接口、`/api/auth/test-connection` 测试接口和一个 MockMvc 单元测试。

当前还没有用户表、邀请码表、登录接口、注册接口、JWT 工具、密码加密、权限拦截器和真实业务测试。

## 零基础学习清单

### 第一层：先理解请求怎么走

先学 HTTP 请求是什么。浏览器或前端发请求，后端 Controller 接住请求，然后调用 Service，Service 写业务规则，Mapper 访问数据库，最后 Controller 返回 JSON。

在本项目里，请求链路是前端请求 `gateway-service:9000`，网关按路径转发到 `auth-service:9101`，auth-service 返回统一格式。

需要能看懂这些概念：GET、POST、请求体、响应体、HTTP 状态码、JSON、Header、Authorization。

### 第二层：理解 Spring Boot 分层

Controller 只负责接请求，不写复杂业务。Service 负责判断规则，比如邀请码能不能用、密码对不对。Mapper 只负责查数据库。Entity 对应数据库表。DTO 是前端传进来的数据。VO 是后端返回给前端的数据。

开发时要守住这个边界。不要把 SQL、密码加密、邀请码规则全塞进 Controller。

### 第三层：理解数据库和表

auth-service 至少需要用户表、邀请码表、邀请码使用记录表。后续可以再加登录日志、刷新令牌、角色变更记录。

先学会表字段、主键、唯一索引、状态字段、创建时间、更新时间。邀请码和用户名这类字段要加唯一索引。密码不能存明文，只能存 BCrypt 加密后的字符串。

### 第四层：理解 MyBatis-Plus

MyBatis-Plus 在这个项目里负责让 Java 对象和 MySQL 表对应起来。Entity 代表一行数据，Mapper 负责增删改查，Service 调 Mapper。

需要先会这些：`@TableName`、`@TableId`、`BaseMapper`、`QueryWrapper` 或 `LambdaQueryWrapper`、插入、按条件查询、更新状态。

### 第五层：理解登录和 JWT

登录不是把用户信息存在后端内存里，而是后端校验账号密码后生成 JWT。前端以后每次请求都在 Header 里带 `Authorization: Bearer <token>`。

后端收到请求后解析 JWT，知道用户 ID 和权限等级。JWT 过期就返回 401。权限不够就返回 403。

### 第六层：理解权限等级

项目固定四级权限：`OWNER`、`ADMIN`、`HELPER`、`USER`。

`OWNER` 权限最高，可以做系统级管理和管理员邀请码。`ADMIN` 可以做日常管理。`HELPER` 可以做辅助审核。`USER` 是普通玩家。

后端权限判断必须生效。前端隐藏按钮只是体验，不能当安全措施。

### 第七层：理解测试

每写一个接口，至少测成功路径和失败路径。比如注册要测邀请码正确、邀请码不存在、邀请码过期、邀请码用完、用户名重复。登录要测密码正确、密码错误、用户被禁用。

先从 Service 单元测试开始，再补 Controller MockMvc 测试。等本地 MySQL 环境稳定后，再补少量集成测试。

### 第八层：理解 Git 协作

开发前从 `feature/p0-backend-microservices` 拉新分支。一个功能一个分支。提交前跑测试。合并时走 Pull Request。

不要提交真实密码、target、日志、node_modules。数据库密码只放本机环境变量。

## 借助 AI 开发的方式

每次新开对话，不要只说“帮我写登录”。要先让 AI 读项目文档和当前代码，再让它给出小步计划。每一步只做一个可验证的变化。

推荐节奏是先让 AI 写测试，再实现功能，再跑测试。测试失败时不要让 AI 猜，要把错误日志贴给它，让它按日志定位。

开发中要让 AI 始终回答这三件事：这个类负责什么，请求进来后怎么走，怎么验证它真的工作了。

## auth-service 第一阶段开发范围

第一阶段只做账号认证闭环，不做后台页面，不做完整用户中心，不做第三方登录。

需要完成的接口是邀请码注册、账号登录、查询当前用户、创建玩家邀请码、禁用邀请码、检查 token 是否有效。

需要完成的数据表是用户表、邀请码表、邀请码使用记录表。

需要完成的安全能力是 BCrypt 密码加密、JWT 生成和解析、基础权限拦截、401 和 403 统一返回。

## 建议接口

注册接口：`POST /api/auth/register`。前端传用户名、密码、昵称、邀请码、Minecraft ID。后端校验邀请码，创建用户，记录使用记录，返回 token 和当前用户信息。

登录接口：`POST /api/auth/login`。前端传用户名和密码。后端校验密码，返回 token 和当前用户信息。

当前用户接口：`GET /api/auth/me`。前端带 token。后端解析 token，返回当前用户。

创建玩家邀请码接口：`POST /api/auth/invite-codes`。管理员或服主创建玩家邀请码。

禁用邀请码接口：`POST /api/auth/invite-codes/{id}/disable`。管理员或服主禁用邀请码。

测试接口：保留 `/api/auth/test-connection`，继续用于验证网关到 auth-service 的连通性。

## 建议表结构方向

`auth_user` 保存官网用户。核心字段包括 id、username、password_hash、nickname、minecraft_id、permission_level、status、created_at、updated_at。

`auth_invite_code` 保存邀请码。核心字段包括 id、code、type、permission_level、max_uses、used_count、expires_at、status、created_by、created_at、updated_at。

`auth_invite_code_usage` 保存邀请码使用记录。核心字段包括 id、invite_code_id、user_id、used_at、used_ip。

第一版不要拆太多表。角色和权限先用 `permission_level` 字段承接，等后台复杂起来再演进。

## 建议开发顺序

先写数据库 DDL，并把它放到 `backend/auth-service/src/main/resources/db/schema.sql` 或统一 SQL 目录里。然后写 Entity 和 Mapper。接着写注册请求 DTO、登录请求 DTO、用户返回 VO。再写密码工具和 JWT 工具。然后写 AuthService。最后写 Controller 和测试。

不要先写网关权限。第一阶段先让 auth-service 自己能注册、登录、解析 token。服务内闭环通了，再考虑 gateway-service 的统一 token 过滤。

## 每次让 AI 开发时的固定要求

让 AI 改代码前必须先说明准备改什么、为什么改。让 AI 每次只处理一个小任务。让 AI 使用现有包名 `games.beiming.website.auth`。让 AI 不写入真实密码。让 AI 跑对应 Maven 测试。让 AI 提交前检查 `git diff`。

如果 AI 要改公共模块，必须说明为什么不能只改 auth-service。公共模块影响所有服务，不能随手动。

## 新对话开场提示

可以在新对话直接粘贴下面这段：

```text
请读取 AGENTS.md、docs/requirements-v5.0.md、docs/system-design-proposal-v5.0.md、docs/code-standards-v1.0.md、docs/development-handoff.md、docs/auth-service-learning-and-ai-development-guide.md。

我要从 auth-service 开始做第一个真实业务模块。目标是实现最小认证闭环：邀请码注册、登录、JWT、查询当前用户、基础权限拦截和对应测试。

请先检查当前 backend/auth-service 和 common 模块代码结构，然后给我一份分阶段实现计划。计划要按 Java 后端新手能理解的方式解释每个类负责什么、请求进来后怎么走、怎么测试。不要直接写代码，先给计划。

数据库密码不要写进仓库。真实密码只通过环境变量注入。不要批量删除文件。不要提交 target、日志、node_modules。
```

## 新对话第一阶段目标

第一阶段只需要让 `auth-service` 自己跑通，不要求前端页面。完成后应该能通过 Postman、浏览器插件或 curl 完成注册和登录，并拿 token 调 `/api/auth/me`。

验收时至少要能跑通这些命令：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend
mvn -pl auth-service -am test
```

本地服务启动后，至少要能请求这些地址：

```text
http://127.0.0.1:9101/api/auth/test-connection
http://127.0.0.1:9101/api/auth/register
http://127.0.0.1:9101/api/auth/login
http://127.0.0.1:9101/api/auth/me
```

通过网关时，地址改成：

```text
http://127.0.0.1:9000/api/auth/test-connection
http://127.0.0.1:9000/api/auth/register
http://127.0.0.1:9000/api/auth/login
http://127.0.0.1:9000/api/auth/me
```

## 暂时不要做的事

暂时不要做找回密码、邮箱验证、短信登录、OAuth 登录、复杂角色表、刷新 token、多端登录管理、后台 UI、前端页面。它们不是第一阶段闭环必须项。

先把注册、登录、token、当前用户、权限拦截做稳。后面的服务才能接上。
