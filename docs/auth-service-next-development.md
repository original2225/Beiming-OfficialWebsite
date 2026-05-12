# auth-service 下一阶段开发指南

这份文档用于新开对话继续开发 `auth-service`。当前仓库已经被同伴推进过，代码结构和之前的 P0 微服务骨架不同。新对话必须先读当前代码，不要套用旧的 Nacos、MySQL、JDK 8 方案。

## 当前事实

当前分支是 `feature/p0-backend-microservices`。最新代码已经变成 Beiming Console 结构，根目录有 React 前端、Spring Boot 服务、Go daemon 和启动脚本。

当前后端不是 Maven 多模块父工程，而是几个独立服务目录。`backend/auth-service` 使用 Spring Boot 3.5.7、Java 21、Spring Web、Spring JDBC、PostgreSQL。服务端口默认是 `8792`。

`auth-service` 已经有注册、登录、查询当前用户、退出、会话校验、用户列表、修改用户接口。它现在用随机 session token，不是 JWT。用户和 session 表由 `AuthService.initializeSchema()` 启动时自动创建。

当前网关在 `backend/api-gateway`，默认端口 `8787`。网关会把 `/api/auth/**`、`/api/users/**`、`/api/cloud/**` 转发给 auth-service。其他 `/api/**` 会先调用 auth-service 的 `/api/auth/validate` 校验登录，再转给 resource-service。

当前已有一个问题要先处理：`.idea/` 是未跟踪文件。它是本地 IDE 配置，不应该进入提交。

## 推荐继续做哪个模块

继续做 `auth-service`，但目标要改成“整理和补强现有认证服务”，不是从零实现。

它现在已经能注册和登录，但代码集中在一个 `AuthService` 大类里，使用 `Map<String,Object>` 当请求和响应，角色叫 `SUPER_ADMIN/ADMIN/MEMBER`，和原需求里的 `OWNER/ADMIN/HELPER/USER` 不一致。下一步最有价值的是把它整理成清晰的 Java 分层，并补测试。

## 零基础学习清单

先学 Spring Boot Controller。你要看懂 `AuthController` 里每个接口路径对应哪个方法。请求进来后，Controller 把 body 和 header 交给 `AuthService`。

再学 Service。`AuthService` 是业务规则所在，比如注册时校验邮箱、校验密码、写用户表、创建 token。现在这个类太大，后续可以拆成认证、用户、会话、密码几个小类。

再学 JDBC。当前项目没有用 MyBatis-Plus，使用的是 `JdbcTemplate`。你要能看懂 `jdbc.update()` 是写数据库，`jdbc.query()` 是查数据库，`RowMapper` 是把数据库一行转成 Java record。

再学 record。`UserRecord` 和 `SessionRecord` 这类 record 是 Java 里的轻量数据对象。它适合承接数据库查询结果，但不适合直接当所有接口的请求对象。

再学异常处理。`ApiException` 带 HTTP 状态码，`ApiExceptionHandler` 负责把异常变成统一 JSON。以后业务失败不要随便 return null，要抛明确异常。

再学密码和 token。当前密码用 PBKDF2 加盐哈希，token 明文只返回给前端，数据库只存 token hash。这点方向是对的。后续要补测试保证密码不会明文落库。

最后学测试。这个服务目前缺少测试。下一步要从 `AuthService` 和 `AuthController` 的关键路径开始补测试。

## 第一阶段目标

第一阶段不要急着改登录方案，不要把 session token 立刻换成 JWT。先把现有功能测住，然后再小步整理。

完成后应该达到这些结果：注册成功会创建用户和 session，重复邮箱注册会失败，登录密码错误会失败，禁用用户无法登录，`/api/auth/me` 无 token 返回 401，有效 token 返回当前用户，普通用户不能改别人资料，SUPER_ADMIN 可以改用户状态和角色。

## 建议开发顺序

先补测试依赖。auth-service 现在 pom 里没有 `spring-boot-starter-test`，需要加 test scope 依赖。

然后写 `AuthServiceTest`。用 H2 会和 PostgreSQL 的 `create index if not exists`、外键语法存在差异，第一版更稳的方式是先用 Testcontainers PostgreSQL。如果暂时不想引入 Docker 测试，可以先写 Controller 层轻量测试，但真正数据库规则还是要补集成测试。

接着整理 DTO 和 VO。把 `Map<String,Object>` 请求改成明确类，比如 `RegisterRequest`、`LoginRequest`、`UpdateUserRequest`、`CurrentUserResponse`、`LoginResponse`。这样 Java 新手看代码时不会猜字段名。

再整理角色枚举。把 `SUPER_ADMIN/ADMIN/MEMBER` 收敛成枚举。是否立刻改成 `OWNER/ADMIN/HELPER/USER` 要看前端是否已经依赖旧角色名。若前端已依赖，先加兼容映射，再逐步替换。

最后拆分 `AuthService`。推荐拆成 `PasswordService`、`SessionService`、`UserRepository`、`AuthService`。Controller 仍只调业务服务，不直接碰 JDBC。

## 新对话开场提示

可以直接粘贴下面这段：

```text
请读取 AGENTS.md、README.md、backend/README.md、backend/auth-service、backend/api-gateway 和 docs/auth-service-next-development.md。

当前仓库已经是 Beiming Console 新结构，不要套用旧的 Nacos/MySQL/JDK8 微服务骨架。auth-service 当前使用 Spring Boot 3.5.7、Java 21、JdbcTemplate、PostgreSQL，默认端口 8792。

我要继续开发 auth-service。目标不是从零写登录，而是补测试、整理 DTO/VO、明确角色枚举，并把 AuthService 大类逐步拆清楚。请先检查当前代码和 git 状态，然后给出一份小步实现计划。不要直接写代码。

注意不要提交 .idea、target、日志、node_modules，不要写入真实数据库密码，不要批量删除文件。
```

## 验证命令

后端 auth-service 构建：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service
mvn test
```

全项目常用构建：

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

暂时不要重写整个 auth-service。不要先换成 JWT。不要把 PostgreSQL 改回 MySQL。不要把现在同伴加的前端、resource-service、node-daemon 回滚。下一步只围绕 auth-service 做可测试的小步改进。
