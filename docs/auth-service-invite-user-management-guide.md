# auth-service 登录注册邀请码与用户管理开发指南

这份文档用于新开对话继续开发 `auth-service`。登录、注册、邀请码、用户管理、会话校验、权限等级都属于 auth-service。它负责回答两个问题：这个请求是谁发的，他有没有资格做这件事。

## 当前项目状态

当前分支是 `feature/p0-backend-microservices`。

当前项目是 Beiming Console 结构，不是旧的 Spring Cloud 多模块官网骨架。后端已有 `api-gateway`、`auth-service`、`resource-service` 和 Go 写的 `node-daemon`。

`auth-service` 使用 Spring Boot 3.5.7、Java 21、Spring Web、Spring JDBC、PostgreSQL。默认端口是 `8792`。配置在 `backend/auth-service/src/main/resources/application.yml`。

当前 auth-service 已经有这些接口：注册、登录、当前用户、退出、会话校验、用户列表、修改用户。代码主要集中在 `AuthController` 和 `AuthService`。现在注册不需要邀请码，用户角色只有 `SUPER_ADMIN`、`ADMIN`、`MEMBER`，请求和响应大量使用 `Map<String,Object>`，还没有测试代码。

## 推荐开发范围

下一阶段就写 auth-service。范围是登录注册邀请码和用户管理。

第一阶段不要碰 node-daemon，也不要做 resource-service。你同伴负责资源执行线，你负责账号权限线。两条线最后在 api-gateway 汇合。

第一阶段目标是让账号系统有清楚边界：用户必须用邀请码注册，管理员可以创建玩家邀请码，超级管理员可以管理用户角色和状态，普通用户只能看和改自己的基础资料。

## 请求怎么走

前端请求先到 `api-gateway:8787`。网关发现路径是 `/api/auth/**` 或 `/api/users/**`，就转发到 `auth-service:8792`。

注册时，Controller 接收注册请求，调用 AuthService。AuthService 校验邀请码，创建用户，记录邀请码使用记录，生成 session token，返回 token 和用户信息。

登录时，Controller 接收邮箱和密码，AuthService 查用户、校验密码、检查账号状态，成功后生成 session token。

用户管理时，Controller 取 Authorization 里的 token，AuthService 用 token 找到当前用户，再判断这个用户能不能查看或修改目标用户。

## 第一阶段要做的功能

保留现有接口：

```text
POST /api/auth/register
POST /api/auth/login
GET /api/auth/me
POST /api/auth/logout
GET /api/auth/validate
GET /api/users
PATCH /api/users/{userId}
```

新增邀请码接口：

```text
POST /api/invite-codes
GET /api/invite-codes
POST /api/invite-codes/{inviteCodeId}/disable
```

注册接口要新增 `inviteCode` 字段。没有邀请码不能注册。邀请码不存在、禁用、过期、超过使用次数时，注册必须失败。

第一个注册用户仍然可以自动成为 `SUPER_ADMIN`，用于初始化系统。后续普通邀请码只能注册 `MEMBER`。管理员邀请码暂时只允许 `SUPER_ADMIN` 创建。

## 建议角色规则

当前代码已有 `SUPER_ADMIN`、`ADMIN`、`MEMBER`。第一阶段先沿用这三个，避免马上破坏前端。

`SUPER_ADMIN` 可以创建管理员邀请码、创建玩家邀请码、禁用邀请码、查看用户列表、修改用户角色、禁用用户。

`ADMIN` 可以创建玩家邀请码、查看用户列表、禁用玩家邀请码，但不能创建管理员邀请码，不能修改 `SUPER_ADMIN`。

`MEMBER` 可以注册、登录、查看自己、修改自己的昵称，不能查看全部用户，不能修改别人，不能创建邀请码。

后续如果要回到旧文档里的 `OWNER/ADMIN/HELPER/USER`，可以单独做一次角色迁移，不要和邀请码功能混在一次提交里。

## 建议数据表

现有表：

```text
beiming_users
beiming_sessions
```

新增表：

```text
beiming_invite_codes
beiming_invite_code_usages
```

`beiming_invite_codes` 建议字段：

```text
id varchar(64) primary key
code varchar(64) not null unique
type varchar(32) not null
role varchar(32) not null
status varchar(32) not null
max_uses int not null
used_count int not null
expires_at bigint not null
created_by varchar(64) not null
created_at bigint not null
updated_at bigint not null
```

`beiming_invite_code_usages` 建议字段：

```text
id varchar(64) primary key
invite_code_id varchar(64) not null
user_id varchar(64) not null
used_at bigint not null
```

第一阶段可以继续在 `AuthService.initializeSchema()` 里建表。后面项目稳定后，再迁到正式 migration。

## 建议新增 Java 类型

先加请求对象，减少 `Map<String,Object>`：

```text
RegisterRequest
LoginRequest
CreateInviteCodeRequest
UpdateUserRequest
```

再加返回对象：

```text
PublicUserView
LoginResponse
InviteCodeView
```

再加枚举：

```text
UserRole
UserStatus
InviteCodeStatus
InviteCodeType
```

再拆服务：

```text
PasswordService
SessionService
InviteCodeService
UserAccountService
```

不要一次全拆。建议先补测试，再加邀请码表和接口，再逐步把 AuthService 里的代码搬出去。

## 建议开发顺序

先加 `spring-boot-starter-test`，补最小测试能力。然后写 Controller 健康检查测试和 AuthService 注册登录测试。

接着新增邀请码表和邀请码 record。先实现 `SUPER_ADMIN` 创建邀请码，再实现注册时消费邀请码。

然后补用户管理权限。`MEMBER` 只能改自己，`ADMIN` 可以看用户列表但不能改 `SUPER_ADMIN`，`SUPER_ADMIN` 可以改所有人。

最后整理 DTO、VO 和枚举。等测试保护住行为后，再拆 AuthService。

## 验收标准

注册必须带邀请码。成功注册后，邀请码使用次数加一，并写入使用记录。

重复邮箱注册返回冲突。密码错误登录返回 401。禁用用户不能登录。没有 token 访问 `/api/auth/me` 返回 401。普通用户访问 `/api/users` 返回 403。管理员可以创建玩家邀请码。超级管理员可以禁用用户。

测试至少覆盖注册成功、邀请码不存在、邀请码禁用、邀请码用完、登录成功、登录密码错误、普通用户越权、管理员修改用户、超级管理员禁用用户。

## 新对话开场提示

可以直接粘贴下面这段：

```text
请读取 AGENTS.md、README.md、backend/README.md、backend/auth-service 和 docs/auth-service-invite-user-management-guide.md。

我要继续开发 auth-service。登录、注册、邀请码、用户管理、会话校验和权限等级都归 auth-service。当前项目是 Beiming Console 结构，不要套用旧的 Nacos/MySQL/JDK8 微服务骨架。auth-service 使用 Spring Boot 3.5.7、Java 21、JdbcTemplate、PostgreSQL，默认端口 8792。

目标是完成第一阶段账号系统：注册必须使用邀请码，管理员能创建和禁用邀请码，用户管理有权限边界，并补对应测试。请先检查当前代码和 git 状态，然后给出小步实现计划。不要直接写代码。

注意不要提交 .idea、target、日志、node_modules，不要写入真实数据库密码，不要批量删除文件。
```

## 常用验证命令

只跑 auth-service：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service
mvn test
```

打包所有 Spring 服务：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite
npm run spring:build
```

启动 auth-service：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite
npm run dev:auth
```

启动全套本地服务：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite
npm run dev:full
```

## 暂时不要做

暂时不要把 session token 改成 JWT。不要改 PostgreSQL。不要重写 api-gateway。不要把云盘接口和邀请码混在一次提交里。不要一次性大拆 AuthService。

先把邀请码注册、用户管理权限和测试做稳。这个做完，auth-service 才能给后面的资源管理提供可靠身份边界。
