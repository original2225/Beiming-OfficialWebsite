# 北冥官网开发接手说明

本文档记录当前仓库的真实状态，方便下次继续开发，不用重新猜项目走到哪一步。

## 当前分支

当前微服务骨架分支是 `feature/p0-backend-microservices`。GitHub 的 main 分支仍可能显示旧页面内容，因为后端骨架还没有合并回 main。继续开发时建议从这个分支拉新分支，做完后再合并回这个分支。

## 当前代码结构

仓库根目录现在保留后端、文档、忽略规则和 AGENTS 说明。`backend/` 是 Maven 多模块工程，`docs/` 里是需求 5.0、系统设计 5.0 和代码规范 1.0。

后端公共模块有 `beiming-common-core`、`beiming-common-web`、`beiming-common-security`。core 负责统一响应和枚举，web 负责异常处理和健康检查，security 只放了权限枚举和注解，暂时还没有真正拦截器。

业务服务已有 `gateway-service`、`auth-service`、`onboarding-service`、`server-service`、`guide-service`、`communication-service`、`exam-service`、`whitelist-service`、`attendance-service`、`profile-service`、`content-service`、`moment-service`、`notification-service`、`admin-service`。

从请求链路看，浏览器或前端先请求 gateway-service 的 9000 端口。网关根据路径把请求转给 Nacos 里注册的业务服务。比如 `/api/auth/test-connection` 会转到 auth-service，auth-service 处理后返回统一的 `ApiResponse`。

## 已经完成的部分

P0 微服务骨架已经搭好。每个服务有启动类、分层目录、配置文件、ping 接口和 MyBatis-Plus 配置。网关已经配置 P0 路由。auth-service 有一个额外的 `/api/auth/test-connection` 测试接口，用来验证网关到服务的连通性。数据库脚本只创建 schema，还没有业务表。

本地曾验证过 Nacos、auth-service、gateway-service 可以跑通。验证地址是 `http://127.0.0.1:9101/api/auth/test-connection` 和 `http://127.0.0.1:9000/api/auth/test-connection`。

## 还没完成的部分

认证主链路还没写。现在没有用户表、邀请码表、角色表、登录接口、JWT、密码加密和权限拦截。入服审核、考试、白名单、考勤、素材投稿、通知、后台管理都只有骨架，没有业务表和业务规则。

前端源码当前不在仓库里。`frontend/` 旧目录只出现过 node_modules 记录，不是可继续开发的 React 工程。正式做前端时需要重新建 React + TypeScript + Vite 工程。

## 下次优先做什么

后端建议先从 auth-service 做最小闭环。先建用户表、角色表、邀请码表，然后实现邀请码注册、登录、BCrypt 密码、JWT 返回，再把 `@RequirePermission` 接到真实拦截逻辑里。这个闭环完成后，其他服务才能可靠识别当前用户是谁、权限是什么。

auth 稳定后，再做入服主流程。onboarding-service 管流程状态，exam-service 管考试，whitelist-service 管白名单申请和审核，attendance-service 管初始分和后续考勤。这个流程跑通后，官网最核心的入服链路才算可用。

展示类服务可以后置。guide、server、communication、content、profile、moment 先提供首页和加入流程需要的数据即可，不要一开始做太多后台配置。

## 同伴怎么并入

同伴开发 Java 后端时，从 `feature/p0-backend-microservices` 新建自己的功能分支。每次只碰一个服务或一条业务链路，提交前跑对应模块测试。合并时走 pull request，目标分支选 `feature/p0-backend-microservices`。

同伴开发 Go + React 的运维能力时，推荐新建独立 `ops-service`。Go 服务负责容器、云盘、虚拟机等真实资源操作，React 页面放到未来 frontend 管理后台里。接入官网时，让 Go 服务注册到同一个 Nacos，服务名固定为 `ops-service`，网关增加 `/api/ops/** -> lb://ops-service`。这样它就是官网里的一个微服务，不需要改现有 Java 服务的内部结构。

admin-service 不建议承载运维资源的真实操作。它更适合做后台聚合、菜单、权限入口和审计入口。资源操作放 ops-service 更清楚，也方便同伴用 Go 独立迭代。

## Git 注意事项

当前仓库里已经有一些 `backend/*/target/` 构建产物被 Git 跟踪。`.gitignore` 已经补了 `backend/**/target/`，可以挡住以后的新产物，但不能自动移除已经被跟踪的文件。清理这些已跟踪产物会产生大量删除记录，需要单独确认后再做。

不要提交真实数据库密码。MySQL 密码只放本机环境变量。不要提交运行日志、target、node_modules、前端 dist。

## 本地常用命令

后端全量测试：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend
mvn test
```

auth 当前测试：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend
mvn -pl auth-service -am -Dtest=AuthTestConnectionControllerTest -DfailIfNoTests=false test
```

启动 auth 和 gateway：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend
mvn -pl auth-service spring-boot:run
mvn -pl gateway-service spring-boot:run
```

PowerShell 建库：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend
$env:MYSQL_PWD="<local password>"
Get-Content -LiteralPath ".\sql\init-p0-schemas.sql" -Raw | mysql --user=root --host=127.0.0.1 --port=3306
```
