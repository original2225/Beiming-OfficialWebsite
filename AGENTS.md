# AGENTS.md

## 用户偏好

- 后端说明要用 Java 语境和大白话解释。用户只熟悉 Java，讲模块时要说明“它负责什么、请求进来后大概怎么走”。
- 前端偏好：交互体验丰富，内容完整但精简，结构得体，美观，不要只做空壳页面。
- 改代码前先说明准备改什么、为什么改；项目状态异常时，要区分“已确认事实”和“根据日志/文档推断”。

## 项目定位

- 项目名：北冥服务器官网。
- 仓库名：`Beiming-OfficialWebsite`。
- 目标：为 Minecraft 服务器提供官网、入服审核、社区入口、内容展示、素材投稿、贡献考勤、白名单审核、消息通知和管理后台。
- 架构方向：前后端分离；后端采用 JDK 8 + Spring Boot 2.7.18 + Spring Cloud 微服务；前端规划为 React + TypeScript + Vite。

## 当前目录结构

```text
.
├── backend/
│   ├── pom.xml
│   ├── README.md
│   ├── sql/init-p0-schemas.sql
│   ├── beiming-common-core/
│   ├── beiming-common-security/
│   ├── beiming-common-web/
│   ├── gateway-service/
│   ├── auth-service/
│   ├── onboarding-service/
│   ├── server-service/
│   ├── guide-service/
│   ├── communication-service/
│   ├── exam-service/
│   ├── whitelist-service/
│   ├── attendance-service/
│   ├── profile-service/
│   ├── content-service/
│   ├── moment-service/
│   ├── notification-service/
│   └── admin-service/
├── docs/
│   ├── requirements-v5.0.md
│   ├── system-design-proposal-v5.0.md
│   └── code-standards-v1.0.md
├── frontend/
│   └── node_modules/
├── .gitignore
└── AGENTS.md
```

说明：各服务下存在 `target/` 构建产物和若干运行日志，这些不应提交。

## 文档现状

- `docs/requirements-v5.0.md`：完整需求文档，定义官网展示、账号邀请码、入服审核、指南、服务器状态、外部交流入口、论坛、工单、素材、成员、考勤、后台等功能。
- `docs/system-design-proposal-v5.0.md`：系统设计预方案，定义微服务拆分、端口、请求链路、数据库 schema、前端路由、部署建议。
- `docs/code-standards-v1.0.md`：前后端代码规范，定义后端分层、统一响应、权限、审计、文件上传、测试、前端结构和 Git 规范。
- `backend/README.md`：后端本地运行说明，包括 MySQL、Redis、Nacos、环境变量、建库脚本和 Maven 命令。

## 后端技术栈

- Java 目标版本：JDK 8。
- Spring Boot：2.7.18。
- Spring Cloud：2021.0.9。
- Spring Cloud Alibaba：2021.0.6.0。
- MyBatis-Plus：3.5.7。
- MySQL Connector/J：8.0.33。
- 数据库：MySQL，按服务拆分 schema。
- 缓存：Redis。
- 服务注册/配置：Nacos。
- 构建工具：Maven，多模块工程。

## 后端模块说明

### 公共模块

- `beiming-common-core`：公共响应、分页对象、错误码、业务枚举。
  - 已有 `ApiResponse`，统一返回 `{ code, message, data }`。
  - 已有 `PageResult`，统一分页返回 `items/page/pageSize/total`。
  - 已有 `ExamTrack`、`ReviewStatus`、`VisibilityScope` 等枚举。
- `beiming-common-security`：权限注解和权限枚举。
  - 已有 `PermissionLevel`。
  - 已有 `@RequirePermission`、`@RequireAnyPermission` 注解。
  - 当前只是注解定义，尚未看到真正的 AOP/拦截器权限校验实现。
- `beiming-common-web`：Web 层公共能力。
  - 已有公共健康检查控制器。
  - 已有全局异常处理 `GlobalExceptionHandler`。

### 业务服务

- `gateway-service`：API 网关，端口 `9000`，通过 Nacos 发现服务，并配置 `/api/auth/**`、`/api/onboarding/**` 等 P0 路由。
- `auth-service`：认证服务，端口 `9101`。当前已有 ping 和 `/api/auth/test-connection` 测试连接接口。
- `onboarding-service`：入服引导服务，端口 `9102`。当前是分层骨架和 ping 接口。
- `server-service`：服务器状态服务，端口 `9103`。当前是分层骨架和 ping 接口。
- `guide-service`：指南/知识库服务，端口 `9104`。当前是分层骨架和 ping 接口。
- `communication-service`：Oopz、QQ群、游戏内聊天入口服务，端口 `9105`。当前是分层骨架和 ping 接口。
- `exam-service`：入服考核服务，端口 `9106`。当前是分层骨架和 ping 接口。
- `whitelist-service`：白名单服务，端口 `9107`。当前是分层骨架和 ping 接口。
- `attendance-service`：考勤积分服务，端口 `9108`。当前是分层骨架和 ping 接口。
- `profile-service`：成员档案服务，端口 `9114`。当前是分层骨架和 ping 接口。
- `content-service`：公告、作品、摄影、专题等内容服务，端口 `9115`。当前是分层骨架和 ping 接口。
- `moment-service`：精彩瞬间、素材投稿、素材审核服务，端口 `9116`。当前是分层骨架和 ping 接口。
- `notification-service`：站内通知服务，端口 `9112`。当前是分层骨架和 ping 接口。
- `admin-service`：后台聚合/管理服务，端口 `9121`。当前是分层骨架和 ping 接口。

## 数据库现状

- `backend/sql/init-p0-schemas.sql` 已创建 P0 schema：
  - `beiming_auth`
  - `beiming_onboarding`
  - `beiming_server`
  - `beiming_guide`
  - `beiming_communication`
  - `beiming_exam`
  - `beiming_whitelist`
  - `beiming_attendance`
  - `beiming_profile`
  - `beiming_content`
  - `beiming_moment`
  - `beiming_notification`
  - `beiming_admin`
- 当前只看到建库 SQL，未看到业务表 DDL。
- 每个业务服务的 `application.yml` 已配置独立数据库连接，账号密码通过环境变量读取。

## 前端现状

- `frontend/` 当前只有 `node_modules/`，没有 `package.json`、`index.html`、`vite.config.js`、`src/`。
- 旧日志显示前端曾用 Vite 5.4.21 启动，包名曾为 `beiming-official-website-frontend`，但源码目前不在工作区。
- 因此当前前端尚未真正开始或源码未恢复。

## 当前进度

### 已完成

- 需求文档、系统设计、代码规范已成文。
- 后端 Maven 多模块工程已搭建。
- P0 后端微服务目录、`pom.xml`、应用入口、`application.yml` 基本都已建立。
- 网关已配置 P0 服务路由。
- 公共响应格式、分页对象、错误码、部分枚举已建立。
- 权限注解和权限等级枚举已建立。
- 各业务服务已按 controller/service/impl/mapper/entity/dto/vo/enums/config 等分层目录搭骨架。
- `auth-service` 已有 `/api/auth/test-connection` 接口和 1 个 MockMvc 单元测试。
- `gateway-service` 和 `auth-service` 从日志看曾启动成功并注册到 Nacos。
- 2026-05-05 执行 `mvn test`：18 个后端模块全部构建/测试成功。

### 未完成

- 前端 React 项目缺失，页面、路由、组件、API 调用都未实现。
- 后端大多数业务仍停留在 ping/骨架阶段，没有真实业务表、实体、Mapper、Service 逻辑和接口。
- 认证登录、邀请码注册、JWT、密码加密、角色权限校验尚未实现。
- `@RequirePermission` 等权限注解尚未接入真正的拦截逻辑。
- 数据库只有 schema 创建脚本，缺少业务表 DDL 和初始化数据。
- 网关只配置了路由，未看到鉴权、限流、跨域、请求日志等完整能力。
- Nacos、Redis、MySQL 依赖需要本地服务配好才能联调。
- auth 日志显示数据库健康检查曾失败：`root` 用户未带密码访问 MySQL 被拒绝。需要设置 `MYSQL_PASSWORD` 等环境变量。
- 后端测试覆盖极少，只有 auth 测试连接接口 1 个测试。
- 文件上传、素材审核、白名单、考试、考勤、通知、后台审计等核心业务都未落地。
- P1/P2 服务如论坛、工单、举报、投票、日历、更新日志、资源、活动等目前没有后端模块实现。

## 本地运行方式

### 环境变量

```powershell
$env:MYSQL_HOST="127.0.0.1"
$env:MYSQL_PORT="3306"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="<本地 MySQL 密码>"
$env:REDIS_HOST="127.0.0.1"
$env:REDIS_PORT="6379"
$env:NACOS_SERVER_ADDR="127.0.0.1:8848"
```

### 初始化数据库

```powershell
cd backend
mysql -uroot -p < sql/init-p0-schemas.sql
```

### 构建和测试

```powershell
cd backend
mvn test
```

### 启动示例

```powershell
cd backend
mvn -pl auth-service spring-boot:run
mvn -pl gateway-service spring-boot:run
```

注意：要通过网关转发到业务服务，需要 Nacos 正常运行，并且对应业务服务已经注册。

## 未完成计划

### P0 阶段

1. 补齐基础设施：确认 JDK 8、Maven、MySQL、Redis、Nacos 可用；修复本地 MySQL 密码环境变量问题。
2. 补齐数据库表：优先实现 auth、onboarding、exam、whitelist、attendance、profile、notification 的核心表。
3. 实现认证主链路：邀请码注册、登录、密码加密、JWT、退出、角色权限、Minecraft ID 绑定。
4. 实现权限拦截：让 `@RequirePermission` 和 `@RequireAnyPermission` 真正生效，后台接口以后端权限为准。
5. 实现入服主流程：入服进度、选择考核方向、考试提交、白名单申请、审核通过、成员档案创建、考勤初始 60 分。
6. 实现官网展示类接口：服务器状态、外部交流入口、指南、公告、成员列表、精彩瞬间。
7. 实现素材投稿和审核：上传校验、待审核、通过/拒绝/需修改、授权方式、通知投稿人。
8. 实现管理后台基础接口：用户、邀请码、题库、白名单、素材、考勤、公告/指南配置。
9. 搭建 React 前端：官网首页、入服流程、登录注册、指南、成员、素材投稿、消息中心、后台基础框架。
10. 补测试：围绕认证、权限、入服、白名单、考勤、素材审核补单元测试和接口测试。

### P1 阶段

- 玩家活跃度榜单和考勤榜单。
- 每月不活跃扣分任务。
- 积分小于等于 0 的白名单移除流程。
- 二次考核规则。
- 工单、举报、论坛、投票。
- 指南搜索、标签、版本记录。
- 工程项目、活动专题、服务器日历、版本更新日志、Cloudreve 资源入口。

### P2 阶段

- Cloudreve API 深度集成。
- 大文件分片上传、视频转码、封面生成。
- SEO、站点地图、多语言、主题切换。
- 在线地图对接。
- Minecraft 插件联动白名单、在线人数、活跃度和成就。
- 消息队列、异步通知、更多数据看板。

## 建议接下来开发步骤

1. 先把 Git 基线整理好：确认 `.gitignore` 生效，不提交 `target/`、`node_modules/`、日志文件；把当前后端骨架和文档作为第一版提交。
2. 固定本地后端环境：用 JDK 8 运行，启动 MySQL、Redis、Nacos，设置 `MYSQL_PASSWORD`，执行 `init-p0-schemas.sql`。
3. 从 `auth-service` 开始开发。原因：注册、登录、权限、用户身份是所有后续功能的基础。
4. 在 `auth-service` 里先做最小闭环：用户表、角色表、邀请码表、注册接口、登录接口、JWT 返回、密码 BCrypt 加密。
5. 做权限基础设施：实现注解拦截或统一权限校验，让 owner/admin/helper/user 的边界可测试。
6. 接着做入服主流程：`onboarding-service`、`exam-service`、`whitelist-service`、`attendance-service` 联起来，先跑通“注册 -> 绑定 Minecraft ID -> 考试 -> 白名单申请 -> 审核通过 -> 初始化 60 分”。
7. 再做展示类服务：`guide-service`、`communication-service`、`server-service`、`profile-service`、`content-service`、`moment-service`，优先提供首页和加入流程需要的数据。
8. 前端在后端 auth 主链路稳定后再正式搭建。先用 React + TypeScript + Vite 建项目，再按首页、注册登录、入服引导、指南、白名单、素材投稿、后台基础页推进。
9. 每完成一个业务模块就补测试，不要等最后统一补。最低要求是核心成功路径、权限拒绝、参数错误、业务冲突都覆盖。
10. 每次联调都走网关 `/api/**`，不要让前端直接调用具体微服务端口。

## 代码规范提醒

- Controller 只接请求、校验参数、调用 Service、返回结果。
- Service 写业务规则；Mapper 只负责数据库读写。
- 不要跨服务直接查库。服务之间通过接口或 Feign 调用。
- 高风险操作必须记录审计：创建管理员邀请码、改角色、白名单通过/拒绝/移除、手动扣分、封禁、审核拒绝、系统配置修改。
- 日志不要输出明文密码、完整 Token、敏感联系方式。
- 上传文件必须限制类型、大小、数量和访问方式，未审核素材不能公开。

## 本次扫描记录

- 扫描日期：2026-05-05。
- 当前 Git 状态：`.gitignore`、`AGENTS.md`、`backend/`、`docs/` 均为未跟踪；`frontend/node_modules/` 被 `.gitignore` 忽略。
- 验证命令：在 `backend/` 下执行 `mvn test`，结果 `BUILD SUCCESS`。
- 测试覆盖现状：只看到 `auth-service` 的 `AuthTestConnectionControllerTest` 运行 1 个测试，其余模块暂无测试。
