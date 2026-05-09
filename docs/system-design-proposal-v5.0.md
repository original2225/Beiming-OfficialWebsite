# 北冥服务器官网系统设计预方案 v5.0

## 1. 文档信息

- 项目名称：北冥服务器官网
- 文档版本：v5.0
- 编写日期：2026-04-29
- 关联需求文档：`docs/requirements-v5.0.md`
- 后端要求：JDK 8、Spring Boot、微服务架构
- 前端要求：React
- 数据库要求：MySQL

## 2. 设计目标

系统面向 Minecraft 服务器官网、入服审核、社区入口、内容展示、贡献考勤与运营管理场景，核心目标是：

- 为玩家提供统一官网入口、指南、服务器状态、外部交流入口和入服审核流程。
- 为成员提供成员档案、作品展示、精彩瞬间、活跃度、贡献考勤和消息通知。
- 为管理团队提供用户、权限、邀请码、审核、白名单、考勤、素材、工单、举报和数据看板。
- 为后续扩展预留 Cloudreve API、Minecraft 插件联动、在线地图接口、社区平台通知等能力。

## 3. 技术栈选型

## 3.1 后端

- JDK：8
- Spring Boot：2.7.18
- Spring Cloud：2021.0.9
- Spring Cloud Alibaba：2021.0.6.0
- Maven：3.8.x
- MyBatis-Plus：3.5.7
- MySQL Connector/J：8.0.33
- Spring Security：随 Spring Boot 2.7.x 管理
- JWT：jjwt 0.11.x 或 Java JWT 4.x
- OpenFeign：随 Spring Cloud 2021.x 管理
- Nacos：2.2.x
- Redis：6.x 或 7.x

说明：Spring Boot 3.x 不支持 JDK 8，因此后端必须固定在 Spring Boot 2.7.x 体系内。

## 3.2 前端

- React：18.x
- TypeScript：5.x
- Vite：5.x
- React Router：6.x
- TanStack Query：服务端状态和接口缓存
- Zustand 或 Redux Toolkit：前端状态管理
- Ant Design：后台管理、表格、表单和审核页面
- Axios：HTTP 请求

## 3.3 基础设施

- 数据库：MySQL 8.0
- 缓存：Redis
- 服务注册与配置：Nacos
- API 网关：Spring Cloud Gateway
- 反向代理：Nginx
- 文件存储：第一阶段可使用本地上传目录或 Cloudreve 分享链接，后续再接 Cloudreve API
- 接口文档：Knife4j 4.x 或 Springdoc 1.6.x
- 日志：Logback + 按服务输出日志

## 4. 总体架构

```text
用户浏览器
  |
React 前端站点
  |
Nginx
  |
gateway-service
  |
  +-- auth-service             用户、登录、邀请码、权限、Minecraft ID 绑定
  +-- onboarding-service       新玩家入服引导进度
  +-- server-service           服务器状态、线路检测、开服时间、地图接口预留
  +-- guide-service            服规、进服指南、游玩指南、工程项目、开放活动说明
  +-- communication-service    Oopz、QQ群、游戏内聊天入口配置
  +-- exam-service             入服考核、题库、考试记录、二次考核
  +-- whitelist-service        白名单申请、审核、移除记录
  +-- attendance-service       考勤积分、贡献记录、榜单、自动扣分任务
  +-- forum-service            论坛分区、帖子、回复、点赞
  +-- report-service           举报、违规记录、处罚记录
  +-- ticket-service           工单、申诉、回复、处理记录
  +-- notification-service     站内通知、审核通知、积分预警
  +-- vote-service             投票、投票记录、结果统计
  +-- profile-service          成员列表、成员详情、成员事迹、公开档案
  +-- content-service          公告、摄影、作品、进度、成就、专题页
  +-- moment-service           精彩瞬间、素材投稿、素材审核、素材展示
  +-- calendar-service         服务器日历、维护日程、工程节点
  +-- changelog-service        版本更新日志
  +-- resource-service         资源列表、Cloudreve 链接、权限控制
  +-- activity-service         活动、报名、结果、奖励
  +-- admin-service            后台聚合、操作日志、系统配置、数据看板
```

## 5. 核心请求链路

## 5.1 邀请码注册

```text
注册页 -> gateway-service -> auth-service
  -> 校验邀请码
  -> 创建用户
  -> 记录邀请码使用记录
  -> 返回 JWT
```

## 5.2 入服审核

```text
玩家选择审核方向 -> exam-service 生成试卷
  -> 玩家提交考试
  -> 客观题自动判分，简答题待审核
  -> whitelist-service 接收白名单申请
  -> admin-service 聚合审核任务
  -> 审核通过后创建成员档案并初始化考勤积分 60 分
```

## 5.3 考勤扣分与移除白名单

```text
attendance-service 每月定时检查
  -> 无上线、无工程、无活动记录
  -> 扣 15 分并记录流水
  -> 积分 <= 0 标记待移除白名单
  -> 管理员确认
  -> whitelist-service 移除白名单
  -> 下次申请进入更高难度二次考核
```

## 5.4 素材投稿与审核

```text
玩家上传素材 -> moment-service 校验文件
  -> 保存文件和元数据
  -> 创建待审核投稿
  -> helper 初审 / admin 终审
  -> 通过后进入精彩瞬间、素材库或专题页
  -> notification-service 通知投稿人
```

## 5.5 外部交流入口

```text
首页 / 帮助中心 / 加入我们 -> communication-service
  -> 返回 Oopz、QQ群、游戏内聊天说明
```

说明：该链路只展示入口和规则，不做聊天同步、不保存聊天记录、不做跨平台 SSO。

## 6. 微服务拆分方案

## 6.1 gateway-service 网关服务

### 职责

- 统一接收前端 API 请求。
- 按路径转发到业务服务。
- 做跨域、基础鉴权、限流和请求日志。

### 路由示例

- `/api/auth/**` -> auth-service
- `/api/onboarding/**` -> onboarding-service
- `/api/server/**` -> server-service
- `/api/guide/**` -> guide-service
- `/api/communication/**` -> communication-service
- `/api/exam/**` -> exam-service
- `/api/whitelist/**` -> whitelist-service
- `/api/attendance/**` -> attendance-service
- `/api/forum/**` -> forum-service
- `/api/report/**` -> report-service
- `/api/ticket/**` -> ticket-service
- `/api/notification/**` -> notification-service
- `/api/vote/**` -> vote-service
- `/api/profile/**` -> profile-service
- `/api/content/**` -> content-service
- `/api/moment/**` -> moment-service
- `/api/calendar/**` -> calendar-service
- `/api/changelog/**` -> changelog-service
- `/api/resource/**` -> resource-service
- `/api/activity/**` -> activity-service
- `/api/admin/**` -> admin-service

网关不直接访问数据库。

## 6.2 auth-service 认证与权限服务

### 职责

- 用户注册、登录、退出。
- 邀请码校验和使用记录。
- JWT 生成与刷新。
- Minecraft ID / UUID 绑定。
- 四级权限：`owner`、`admin`、`helper`、`user`。

### 核心表

- `sys_user`
- `sys_role`
- `sys_permission`
- `sys_user_role`
- `game_profile`
- `invite_code`
- `invite_code_usage`
- `login_record`

### 关键规则

- owner 可创建管理员邀请码。
- owner/admin 可创建玩家邀请码。
- helper/user 不可创建邀请码。
- user 不可访问后台接口。
- 高风险接口必须校验 owner 或 admin 权限。

## 6.3 onboarding-service 入服引导服务

### 职责

- 跟踪玩家入服流程进度。
- 展示当前步骤、完成状态、失败原因和下一步入口。

### 核心表

- `onboarding_progress`
- `onboarding_step_record`

### 关键步骤

- 邀请码注册
- 绑定 Minecraft ID
- 阅读服规
- 选择审核方向
- 完成考试
- 提交白名单
- 等待审核
- 审核通过

## 6.4 server-service 服务器状态服务

### 职责

- 查询 Minecraft 服务器状态。
- 管理多线路配置。
- 检测线路连通性。
- 统计在线人数、延迟、峰值和历史状态。
- 计算开服至今时间。
- 预留在线地图接口配置。

### 核心表

- `server_config`
- `server_line`
- `server_status_record`
- `server_online_snapshot`
- `map_integration_config`

### 关键设计

- 状态查询使用 Redis 缓存 15 到 60 秒。
- 在线地图暂不实现页面，只保存接口预留配置。

## 6.5 guide-service 指南与知识库服务

### 职责

- 管理服规中心。
- 管理进服指南、游玩指南、玩家指南。
- 管理现役工程项目和开放活动说明。
- 管理分类、标签、搜索、目录、置顶和版本记录。

### 核心表

- `guide_category`
- `guide_article`
- `guide_article_version`
- `guide_tag`
- `guide_article_tag`
- `project`
- `project_participation`
- `guide_featured`

## 6.6 communication-service 外部交流入口服务

### 职责

- 管理 Oopz 入口。
- 管理 QQ群入口。
- 管理游戏内聊天说明。
- 向首页、帮助中心、加入我们页面提供交流入口配置。

### 核心表

- `communication_entry`
- `oopz_entry`
- `qq_group_entry`
- `ingame_chat_guide`

### 关键设计

- 只保存入口、说明、规则、排序和可见范围。
- 不同步 Oopz、QQ群、游戏内聊天消息。
- 不保存外部聊天记录。
- 不做跨平台 SSO。

## 6.7 exam-service 入服考核服务

### 职责

- 管理红石、后期、建筑、其他四类审核方向。
- 管理题库、试卷、考试记录。
- 支持二次考核规则。

### 核心表

- `exam_track`
- `exam_question`
- `exam_paper`
- `exam_paper_question`
- `exam_submission`
- `exam_answer`
- `exam_review`
- `exam_retry_rule`

### 关键设计

- 考试仅用于入服审核和重新入服审核。
- 二次考核难度必须高于上次，可通过更高通过分、更多简答题或独立高难题库实现。

## 6.8 whitelist-service 白名单服务

### 职责

- 白名单申请。
- 白名单审核。
- 白名单移除记录。
- 后续对接 Minecraft 插件自动写入或移除白名单。

### 核心表

- `whitelist_application`
- `whitelist_review`
- `whitelist_record`
- `whitelist_removal_record`

### 服务依赖

- 调用 auth-service 校验 Minecraft ID 绑定。
- 调用 exam-service 查询考试通过状态。
- 调用 attendance-service 查询是否需要二次考核。
- 调用 notification-service 发送审核通知。

## 6.9 attendance-service 考勤与贡献服务

### 职责

- 初始化成员考勤积分 60 分。
- 管理贡献记录、积分流水、活跃度榜单、业绩考勤榜单。
- 每月检查不活跃玩家并扣 15 分。
- 标记积分小于等于 0 的成员为待移除白名单。

### 核心表

- `attendance_account`
- `attendance_score_log`
- `contribution_record`
- `activity_score_record`
- `attendance_deduction_record`
- `leaderboard_snapshot`

### 关键设计

- 所有分数变化必须有流水。
- 自动扣分任务必须用周期标识避免重复扣分。
- 手动加减分必须填写原因。
- 积分小于等于 0 不直接删除白名单，而是进入可追溯处理流程。

## 6.10 forum-service 论坛服务

### 职责

- 论坛分区管理。
- 发帖、回帖、编辑、删除。
- 点赞、收藏、浏览量统计。
- 置顶、加精、锁定。

### 核心表

- `forum_category`
- `forum_post`
- `forum_reply`
- `forum_like`
- `forum_favorite`

论坛是沉淀型内容，不替代 Oopz、QQ群和游戏内聊天。

## 6.11 report-service 举报与处罚服务

### 职责

- 玩家举报。
- 举报处理。
- 违规处罚记录。
- 处罚公示和内部记录。

### 核心表

- `report`
- `report_evidence`
- `report_process_record`
- `punishment`

## 6.12 ticket-service 工单与申诉服务

### 职责

- 工单创建、回复、分配、关闭。
- 封禁申诉、白名单问题、账号问题、资源问题、BUG 反馈、素材争议。

### 核心表

- `ticket`
- `ticket_reply`
- `ticket_attachment`
- `ticket_process_record`

## 6.13 notification-service 消息服务

### 职责

- 站内消息。
- 审核结果通知。
- 工单回复通知。
- 积分变动和扣分预警。
- 后续扩展 QQ、Oopz、邮件通知。

### 核心表

- `notification`
- `notification_template`
- `notification_read_record`
- `notification_channel_config`

## 6.14 vote-service 投票服务

### 职责

- 创建投票。
- 管理投票选项、时间和权限。
- 用户投票。
- 结果统计和归档。

### 核心表

- `vote`
- `vote_option`
- `vote_record`

## 6.15 profile-service 成员档案服务

### 职责

- 成员列表。
- 成员详情。
- 成员事迹。
- 成员皮肤、Minecraft ID、昵称、身份组、加入时间、状态。

### 核心表

- `member_profile`
- `member_group`
- `member_story`
- `member_story_media`
- `player_profile`

### 关键设计

- 成员皮肤优先通过 Minecraft ID / UUID 获取。
- 获取失败时使用默认皮肤或后台头像。
- 成员事迹公开前需要审核。

## 6.16 content-service 内容展示服务

### 职责

- 公告。
- 摄影作品。
- 成员作品。
- 服务器进度。
- 成就和里程碑。
- 内容专题页。

### 核心表

- `announcement`
- `photo`
- `work`
- `server_progress`
- `milestone`
- `content_collection`
- `featured_content`

## 6.17 moment-service 精彩瞬间与素材服务

### 职责

- 精彩瞬间。
- 精彩活动记录。
- 素材投稿。
- 素材审核。
- 素材授权和展示权限。

### 核心表

- `moment`
- `moment_event`
- `media_asset`
- `media_submission`
- `media_review`
- `media_license`
- `media_category`
- `media_tag`
- `media_relation`

### 文件处理流程

```text
用户上传 -> 校验权限、类型、大小、数量
  -> 保存文件
  -> 创建 media_asset
  -> 创建 media_submission 待审核
  -> helper 初审 / admin 终审
  -> 通过后进入素材库、精彩瞬间或专题页
```

## 6.18 calendar-service 日历服务

### 职责

- 服务器日历。
- 活动时间。
- 维护时间。
- 工程节点。
- 投票截止时间。
- 版本更新时间。

### 核心表

- `calendar_event`
- `calendar_event_relation`

## 6.19 changelog-service 更新日志服务

### 职责

- 服务器版本更新日志。
- 插件变更。
- 规则调整。
- 资源包更新。
- 地图更新记录。

### 核心表

- `changelog`
- `changelog_item`
- `changelog_relation`

## 6.20 resource-service 资源服务

### 职责

- 资源列表。
- 资源分类、版本和更新日志。
- Cloudreve 分享链接或 API 对接。
- 下载权限控制。

### 核心表

- `resource`
- `resource_category`
- `resource_version`
- `resource_access_rule`

## 6.21 activity-service 活动服务

### 职责

- 活动发布。
- 活动报名。
- 活动结果。
- 活动奖励。
- 关联精彩瞬间、视频素材、获奖作品和成员事迹。

### 核心表

- `activity`
- `activity_signup`
- `activity_result`
- `activity_reward`

## 6.22 admin-service 后台聚合服务

### 职责

- 为后台提供聚合接口。
- 汇总待审核白名单、素材、作品、帖子、举报、工单、成员事迹和简答题。
- 管理系统配置。
- 记录管理员操作日志。
- 提供数据看板。

### 核心表

- `admin_operation_log`
- `system_config`
- `site_seo_config`
- `brand_asset`
- `partner_link`
- `backup_record`

### 关键设计

- admin-service 不重复保存业务主数据。
- 管理动作通过 OpenFeign 调用对应业务服务。
- 删除、封禁、审核拒绝、移除白名单、手动扣分等高风险操作必须二次确认并记录审计日志。

## 7. 数据库设计

## 7.1 Schema 拆分

第一阶段使用一个 MySQL 实例，按服务拆分 schema：

- `beiming_auth`
- `beiming_onboarding`
- `beiming_server`
- `beiming_guide`
- `beiming_communication`
- `beiming_exam`
- `beiming_whitelist`
- `beiming_attendance`
- `beiming_forum`
- `beiming_report`
- `beiming_ticket`
- `beiming_notification`
- `beiming_vote`
- `beiming_profile`
- `beiming_content`
- `beiming_moment`
- `beiming_calendar`
- `beiming_changelog`
- `beiming_resource`
- `beiming_activity`
- `beiming_admin`

这样部署简单，同时保留微服务数据边界，后续可以按服务独立拆库。

## 7.2 通用字段

大多数业务表包含：

- `id`：主键，建议雪花 ID 或数据库自增 ID。
- `created_at`：创建时间。
- `updated_at`：更新时间。
- `created_by`：创建人。
- `updated_by`：更新人。
- `deleted`：逻辑删除标记。
- `status`：业务状态。

## 7.3 跨服务原则

- 服务不能直接读取其他服务数据库。
- 跨服务数据通过接口调用。
- 高频展示字段可以保存快照，例如作者昵称、头像、Minecraft ID。
- 快照字段不是主数据，主数据仍归属对应服务。

## 8. 文件与素材存储设计

## 8.1 第一阶段方案

第一阶段推荐先使用官网后端接收上传，文件保存到服务器指定目录，数据库保存文件元数据。Cloudreve 先作为资源分享入口使用。

后续可以升级为：官网后端接收上传后调用 Cloudreve API 保存文件，数据库保存 Cloudreve 文件 ID 或分享链接。

## 8.2 文件安全

- 限制上传后缀白名单。
- 禁止 `.exe`、`.bat`、`.cmd`、`.ps1`、`.sh`、`.js`、`.html` 等危险文件直接公开访问。
- 限制单文件大小、单次上传数量和每日上传次数。
- 上传素材必须先审核后公开。
- 后续可接入杀毒扫描或对象存储安全扫描。

## 9. 前端应用设计

## 9.1 项目结构

```text
frontend/
  src/
    app/
    pages/
      home/
      server-status/
      communication/
      announcements/
      guides/
      exam/
      whitelist/
      members/
      leaderboards/
      moments/
      submissions/
      forum/
      tickets/
      reports/
      notifications/
      resources/
      calendar/
      changelog/
      help/
      user-center/
      admin/
    components/
    layouts/
    services/
    stores/
    i18n/
    themes/
```

## 9.2 页面路由

- `/` 首页
- `/status` 服务器状态
- `/communication` 外部交流入口
- `/communication/oopz` Oopz 入口
- `/communication/qq` QQ群入口
- `/communication/ingame` 游戏内聊天说明
- `/announcements` 公告
- `/rules` 服规中心
- `/join-guide` 进服指南
- `/play-guide` 游玩指南
- `/player-guide` 玩家指南
- `/exam` 入服考核
- `/whitelist` 白名单申请
- `/onboarding` 入服引导进度
- `/projects` 现役工程
- `/open-activities` 开放活动
- `/members` 成员列表
- `/members/:memberId` 成员详情
- `/leaderboards/activity` 玩家活跃度榜单
- `/leaderboards/attendance` 业绩考勤榜单
- `/moments` 精彩瞬间
- `/submissions/new` 素材投稿
- `/forum` 论坛
- `/tickets` 工单与申诉
- `/reports/new` 举报入口
- `/notifications` 消息中心
- `/calendar` 服务器日历
- `/changelog` 版本更新日志
- `/resources` 资源下载
- `/help` 帮助中心
- `/user` 用户中心
- `/admin` 管理后台

## 9.3 前端权限

- 未登录用户访问受限页面时跳转登录页。
- user 不能进入 `/admin`。
- helper、admin、owner 根据后端返回权限渲染不同后台菜单。
- 前端权限只优化体验，真实权限以后端校验为准。

## 10. 接口规范

## 10.1 统一响应格式

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

分页响应：

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

## 10.2 常用错误码

- `0`：成功
- `400`：请求参数错误
- `401`：未登录或登录过期
- `403`：无权限
- `404`：资源不存在
- `409`：业务冲突，例如重复投票、重复绑定
- `410`：邀请码过期或失效
- `413`：上传文件过大
- `415`：文件类型不支持
- `429`：请求过于频繁
- `500`：服务器内部错误

## 10.3 认证方式

登录成功后返回 JWT，前端请求携带：

```text
Authorization: Bearer <token>
```

网关做基础 Token 校验，业务服务做细粒度权限判断。

## 11. 服务通信

## 11.1 同步调用

服务之间使用 OpenFeign：

- whitelist-service 调用 auth-service 查询 Minecraft ID 绑定状态。
- whitelist-service 调用 exam-service 查询考试是否通过。
- whitelist-service 调用 attendance-service 查询是否需要二次考核。
- attendance-service 调用 whitelist-service 标记待移除白名单。
- admin-service 调用 moment-service 查询待审核素材。
- admin-service 调用 report-service 查询待处理举报。
- admin-service 调用 ticket-service 查询待处理工单。
- moment-service 调用 profile-service 查询关联成员快照。
- activity-service 调用 moment-service 查询活动关联素材。

## 11.2 异步事件

第一阶段不强制引入消息队列。后续可引入 RabbitMQ 或 RocketMQ，用于：

- 审核结果通知。
- 素材上传后的缩略图生成。
- 视频转码任务。
- 每月自动扣分任务结果。
- 工单和举报状态变更通知。
- 用户注册通知。

## 12. 部署预方案

## 12.1 开发环境

第一阶段至少启动：

- MySQL
- Redis
- Nacos
- gateway-service
- auth-service
- onboarding-service
- server-service
- guide-service
- communication-service
- exam-service
- whitelist-service
- attendance-service
- profile-service
- content-service
- moment-service
- notification-service
- admin-service
- frontend

论坛、投票、完整活动、工单、举报、日历、更新日志、资源深度集成可以第二阶段补齐。

## 12.2 生产环境

推荐 Docker Compose 起步：

- Nginx
- MySQL
- Redis
- Nacos
- gateway-service
- 各业务服务
- frontend 静态站点
- 文件存储目录或 Cloudreve

请求入口：

```text
域名 -> Nginx -> React 静态资源
             -> /api 转发到 gateway-service
             -> /uploads 或 Cloudreve 访问素材文件
```

## 12.3 端口规划

- frontend dev：5173
- Nginx：80 / 443
- gateway-service：9000
- Nacos：8848
- auth-service：9101
- onboarding-service：9102
- server-service：9103
- guide-service：9104
- communication-service：9105
- exam-service：9106
- whitelist-service：9107
- attendance-service：9108
- forum-service：9109
- report-service：9110
- ticket-service：9111
- notification-service：9112
- vote-service：9113
- profile-service：9114
- content-service：9115
- moment-service：9116
- calendar-service：9117
- changelog-service：9118
- resource-service：9119
- activity-service：9120
- admin-service：9121

## 13. 第一阶段落地顺序

## 13.1 P0 服务

- gateway-service
- auth-service
- onboarding-service
- server-service
- guide-service
- communication-service
- exam-service
- whitelist-service
- attendance-service
- profile-service
- content-service
- moment-service
- notification-service
- admin-service
- frontend

这些服务完成“展示、邀请码注册、查指南、看外部交流入口、选择方向参加入服考核、申请白名单、初始化考勤积分、看成员、上传素材、审核展示、收通知、查服务器状态”的主流程。

## 13.2 P1 服务

- forum-service
- report-service
- ticket-service
- vote-service
- calendar-service
- changelog-service
- resource-service 基础能力
- activity-service 基础能力

## 13.3 P2 增强

- Cloudreve API 深度集成
- 大文件分片上传
- 视频转码和封面自动生成
- SEO 站点地图
- 在线地图接口对接
- Oopz / QQ / 邮件通知扩展
- Minecraft 插件联动
- 消息队列和异步任务中心

## 14. 风险与约束

- 微服务会增加部署和联调成本，第一阶段必须围绕 P0 主流程开发。
- JDK 8 限制后端不能升级到 Spring Boot 3.x。
- 素材上传涉及文件安全，需要限制类型、大小、访问方式和上传频率。
- Oopz、QQ群、游戏内聊天第一阶段只展示入口，不做自动同步。
- Cloudreve 属于外部系统，不可用时不能影响官网主流程。
- 考勤扣分和白名单移除属于高风险流程，必须可追溯并由管理员确认。
- 成员皮肤接口可能失败，前端和后端都需要默认兜底。

## 15. 待确认事项

- Oopz 入口是否游客可见，还是只对注册玩家或成员可见。
- QQ群是否有多个群，例如官方群、审核群、成员群、活动群。
- 游戏内聊天规则是否需要按频道区分。
- 红石、后期、建筑、其他四类考核的具体题库和通过分。
- 二次考核难度如何定义。
- 考勤积分加分守则如何量化。
- 一个月未上线、未参与工程、未参与活动的判断来源。
- 积分小于等于 0 后是否自动提交移除白名单待办，还是只提醒管理员。
- 玩家上传素材最终存本地、Cloudreve，还是对象存储。
- 是否需要对接 Minecraft 插件，实现白名单、在线玩家、活跃度和成就自动同步。
