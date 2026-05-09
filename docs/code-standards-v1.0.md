# 北冥服务器官网前后端代码规范 v1.0

## 1. 文档信息

- 项目名称：北冥服务器官网
- 文档版本：v1.0
- 编写日期：2026-04-29
- 关联需求文档：`docs/requirements-v5.0.md`
- 关联系统设计：`docs/system-design-proposal-v5.0.md`
- 后端技术栈：JDK 8、Spring Boot 2.7.18、Spring Cloud 2021.x、MyBatis-Plus、MySQL、Redis、Nacos
- 前端技术栈：React 18、TypeScript、Vite、React Router、TanStack Query、Ant Design

## 2. 总体原则

- 前后端分离，前端只调用 API 网关，不直接调用具体微服务。
- 微服务按业务边界拆分，每个服务只负责自己的数据和业务规则。
- 服务之间不能直接访问对方数据库，跨服务数据必须通过接口调用。
- 所有权限判断以后端为准，前端隐藏按钮只做体验优化。
- 所有高风险操作必须记录审计日志，例如封禁、移除白名单、手动扣分、审核拒绝、创建管理员邀请码。
- Oopz、QQ群、游戏内聊天仅作为外部交流入口展示，不实现聊天同步、聊天记录保存或跨平台 SSO。

## 3. 后端代码规范

## 3.1 项目结构

每个微服务使用独立 Spring Boot 应用，建议结构：

```text
service-name/
  src/main/java/games/beiming/website/{service}/
    Application.java
    controller/
    service/
    service/impl/
    mapper/
    entity/
    dto/
    vo/
    enums/
    config/
    security/
    feign/
    job/
    exception/
```

命名示例：

- `auth-service`
- `exam-service`
- `attendance-service`
- `communication-service`
- `moment-service`

Java 包名统一使用：

```text
games.beiming.website.{service}
```

## 3.2 分层职责

- `controller`：只负责接收请求、参数校验、调用 service、返回结果。
- `service`：负责业务逻辑，例如邀请码校验、考试判分、考勤扣分。
- `mapper`：只负责数据库读写，不写业务判断。
- `entity`：数据库表结构映射。
- `dto`：前端请求参数或服务间调用参数。
- `vo`：返回给前端的展示对象。
- `enums`：状态、角色、审核方向、内容类型等枚举。
- `feign`：跨服务 HTTP 调用客户端。
- `job`：定时任务，例如每月考勤扣分。

禁止在 Controller 中直接操作 Mapper。

## 3.3 命名规范

- 类名使用 `UpperCamelCase`，例如 `InviteCodeService`。
- 方法名和变量名使用 `lowerCamelCase`，例如 `createInviteCode`。
- 常量使用全大写加下划线，例如 `DEFAULT_ATTENDANCE_SCORE`。
- 数据库表名使用小写下划线，例如 `attendance_score_log`。
- 接口路径使用小写短横线或业务名词，例如 `/api/attendance/score-logs`。

## 3.4 枚举规范

核心业务状态必须使用枚举，不使用散落字符串。

必须定义的枚举包括：

- `PermissionLevel`：`OWNER`、`ADMIN`、`HELPER`、`USER`
- `ExamTrack`：`REDSTONE`、`LATE_GAME`、`BUILDING`、`GENERAL`
- `ReviewStatus`：`DRAFT`、`PENDING_REVIEW`、`APPROVED`、`REJECTED`、`NEED_CHANGES`、`TAKEN_DOWN`、`ARCHIVED`
- `VisibilityScope`：`PUBLIC`、`MEMBER_ONLY`、`ADMIN_ONLY`、`ARCHIVED`
- `TicketStatus`：`PENDING`、`PROCESSING`、`WAITING_USER`、`RESOLVED`、`CLOSED`

数据库中保存枚举字符串，避免保存不透明数字。

## 3.5 接口返回规范

统一返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

分页返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [],
    "page": 1,
    "pageSize": 20,
    "total": 100
  }
}
```

错误码：

- `400`：请求参数错误
- `401`：未登录或登录过期
- `403`：无权限
- `404`：资源不存在
- `409`：业务冲突
- `410`：邀请码过期或失效
- `413`：上传文件过大
- `415`：文件类型不支持
- `429`：请求过于频繁
- `500`：服务器内部错误

## 3.6 权限规范

- `user` 不能访问后台接口。
- `helper` 只能做初审、回复、协助维护，不能做高风险操作。
- `admin` 可做日常管理和终审，但不能创建 owner，不能绕过审计。
- `owner` 拥有全部权限，负责系统配置、管理员邀请码和最高风险操作。

高风险接口必须显式标注权限要求，例如：

```java
@RequirePermission(PermissionLevel.OWNER)
```

或：

```java
@RequireAnyPermission({PermissionLevel.OWNER, PermissionLevel.ADMIN})
```

如果暂时没有自定义注解，必须在 Service 层集中校验，不能只依赖前端。

## 3.7 数据库规范

每个微服务使用独立 schema，例如：

- `beiming_auth`
- `beiming_exam`
- `beiming_attendance`
- `beiming_moment`

通用字段：

- `id`
- `created_at`
- `updated_at`
- `created_by`
- `updated_by`
- `deleted`
- `status`

删除数据默认使用逻辑删除。涉及审计、白名单、考勤积分、处罚、审核记录的数据不允许物理删除。

## 3.8 事务规范

必须使用事务的场景：

- 邀请码校验和用户创建。
- 白名单审核通过、成员档案创建、考勤积分初始化。
- 考勤积分加减分和流水记录。
- 素材审核状态变更和审核记录写入。
- 举报处理和处罚记录写入。

跨服务不做复杂分布式事务。需要跨服务一致性时，使用“状态机 + 审核记录 + 可重试补偿”的方式。

## 3.9 日志与审计

业务日志使用结构化信息，避免只写“失败了”。

必须记录审计日志的操作：

- 创建管理员邀请码。
- 修改权限角色。
- 白名单通过、拒绝、移除。
- 手动加减考勤分。
- 审核拒绝素材、作品、成员事迹。
- 封禁账号、禁言、处罚公示。
- 修改系统配置。

日志中不能输出明文密码、完整 Token、敏感联系方式。

## 3.10 文件上传规范

- 上传接口必须校验登录状态。
- 限制文件类型、大小、数量和频率。
- 素材上传后默认进入待审核状态。
- 未审核文件不能公开展示。
- 禁止 `.exe`、`.bat`、`.cmd`、`.ps1`、`.sh`、`.js`、`.html` 等危险文件直接公开访问。
- 文件元数据必须记录上传人、原始文件名、大小、类型、存储路径、可见范围和审核状态。

## 3.11 测试规范

后端至少覆盖：

- 邀请码注册成功、过期、禁用、超次数。
- owner/admin/helper/user 权限边界。
- 红石、后期、建筑、其他四类考试提交。
- 二次考核难度高于上次考核。
- 白名单通过后初始化 60 分。
- 一个月不活跃扣 15 分，且同周期不重复扣分。
- 积分小于等于 0 后进入待移除白名单流程。
- 素材上传、审核通过、拒绝、要求修改。
- 工单创建、回复、关闭。
- 举报处理和处罚记录。

## 4. 前端代码规范

## 4.1 项目结构

推荐结构：

```text
frontend/src/
  app/
  pages/
  components/
  layouts/
  services/
  stores/
  hooks/
  types/
  utils/
  i18n/
  themes/
```

页面目录按业务拆分：

- `pages/home`
- `pages/communication`
- `pages/exam`
- `pages/whitelist`
- `pages/members`
- `pages/leaderboards`
- `pages/moments`
- `pages/admin`

## 4.2 TypeScript 规范

- 禁止使用 `any` 作为默认方案。
- API 返回类型必须定义接口。
- 枚举值与后端保持一致。
- 表单数据、列表项、详情对象分别定义类型。

示例：

```ts
export type PermissionLevel = 'OWNER' | 'ADMIN' | 'HELPER' | 'USER';
```

## 4.3 组件规范

- 页面组件负责组织布局和调用数据。
- 通用组件放在 `components`。
- 后台表格、审核弹窗、状态标签应抽成复用组件。
- 组件 Props 必须定义类型。
- 单个组件过大时按“筛选区、列表区、弹窗区”拆分。

## 4.4 API 调用规范

- 所有请求统一从 `services` 发出。
- 不允许在组件中直接拼接底层请求逻辑。
- Token 统一由请求拦截器注入。
- `401` 统一跳转登录。
- `403` 统一显示无权限提示。
- 分页接口统一使用 `page`、`pageSize`。

## 4.5 路由规范

核心路由：

- `/communication`
- `/communication/oopz`
- `/communication/qq`
- `/communication/ingame`
- `/exam`
- `/whitelist`
- `/onboarding`
- `/members`
- `/leaderboards/activity`
- `/leaderboards/attendance`
- `/moments`
- `/submissions/new`
- `/admin`

后台路由必须做前端权限过滤，但后端仍必须再次校验。

## 4.6 权限展示规范

- `user` 不显示后台入口。
- `helper` 只显示初审、工单回复、普通内容处理相关页面。
- `admin` 显示日常管理页面。
- `owner` 显示系统配置、管理员邀请码、备份、最高风险操作页面。

前端不得通过隐藏按钮代替后端权限校验。

## 4.7 表单规范

- 所有表单必须有加载态、错误态、成功态。
- 审核拒绝、手动扣分、封禁、移除白名单必须要求填写原因。
- 文件上传必须显示进度和失败原因。
- 危险操作必须二次确认。

## 4.8 UI 与交互规范

- 官网页面以清晰、实用、信息密度适中为主。
- 管理后台优先使用表格、筛选、抽屉、弹窗和状态标签。
- 外部交流入口要明确写出 Oopz、QQ群、游戏内聊天分别适合什么用途。
- 考勤积分、白名单状态、审核状态必须用清晰标签展示。
- 移动端需要保证核心流程可用：注册、入服引导、考试、白名单、素材投稿、查看消息。

## 4.9 前端测试规范

前端至少覆盖：

- 登录状态和无权限页面。
- 入服引导进度展示。
- 四类考试方向选择。
- 白名单申请表单校验。
- Oopz、QQ群、游戏内聊天入口展示。
- 素材上传表单校验。
- 成员列表和成员详情。
- 活跃度榜单和业绩考勤榜单。
- 后台不同权限菜单展示。

## 5. 接口与前后端协作规范

## 5.1 API 命名

- 使用 REST 风格。
- 列表：`GET /api/{module}/items`
- 详情：`GET /api/{module}/items/{id}`
- 创建：`POST /api/{module}/items`
- 更新：`PUT /api/{module}/items/{id}`
- 状态变更：`PATCH /api/{module}/items/{id}/status`
- 审核：`PATCH /api/{module}/items/{id}/review`

## 5.2 字段命名

- JSON 字段使用 `lowerCamelCase`。
- 时间字段使用 ISO 8601 字符串或统一时间格式。
- 金额、分数、次数等数值字段必须明确单位。
- 状态字段使用枚举字符串。

## 5.3 分页与筛选

统一分页参数：

- `page`
- `pageSize`

常见筛选参数：

- `keyword`
- `status`
- `type`
- `startTime`
- `endTime`

## 5.4 兼容规范

- 后端新增字段不能破坏旧前端。
- 前端不能依赖未写入接口文档的字段。
- 删除字段必须先废弃，再移除。

## 6. Git 与提交规范

## 6.1 分支命名

- `feature/{module}-{desc}`
- `fix/{module}-{desc}`
- `docs/{desc}`
- `refactor/{module}-{desc}`

## 6.2 提交信息

建议格式：

```text
type(scope): summary
```

示例：

- `feat(auth): add invite code registration`
- `feat(attendance): add monthly deduction records`
- `docs(requirements): add communication entry rules`
- `fix(moment): validate upload file type`

## 7. 禁止事项

- 禁止在前端硬编码权限结果。
- 禁止后端跨服务直接查库。
- 禁止将考试用于入服审核以外的娱乐答题场景。
- 禁止未审核素材公开展示。
- 禁止无审计记录地移除白名单、封禁账号、手动扣分。
- 禁止 Oopz、QQ群、游戏内聊天自动同步到官网。
- 禁止日志输出密码、完整 Token 和敏感隐私。

## 8. 第一阶段最低执行标准

第一阶段至少做到：

- 后端统一响应格式。
- owner/admin/helper/user 权限可用。
- P0 服务使用一致分层结构。
- 前端 API 调用集中管理。
- 后台菜单按权限显示。
- 上传、审核、白名单、考勤积分都有审计记录。
- 关键业务有测试覆盖。
