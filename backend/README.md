# Beiming Backend

北冥服务器官网后端 P0 微服务骨架。这里先把 Java 后端的服务边界、统一返回、网关路由、Nacos 注册、MySQL/Redis 配置模板搭起来，业务逻辑后续从 auth-service 开始补。

## 技术基线

JDK 编译目标是 1.8。Spring Boot 使用 2.7.18，Spring Cloud 使用 2021.0.9，Spring Cloud Alibaba 使用 2021.0.6.0。构建工具是 Maven 多模块工程。

真实密码不要写进仓库。MySQL、Redis、Nacos 都从环境变量读，本地没有设置时使用配置里的默认 host 和端口。

```powershell
$env:MYSQL_HOST="127.0.0.1"
$env:MYSQL_PORT="3306"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="<local password>"
$env:REDIS_HOST="127.0.0.1"
$env:REDIS_PORT="6379"
$env:NACOS_SERVER_ADDR="127.0.0.1:8848"
```

## 模块地图

公共模块里，beiming-common-core 放统一响应、分页对象、错误码和通用枚举。beiming-common-web 放全局异常处理和公共健康检查。beiming-common-security 放权限等级和权限注解，目前只是预留，真正的拦截校验还没实现。

gateway-service 是入口网关，端口 9000，请求进来后按 `/api/**` 路径转发到 Nacos 里对应的服务。auth-service 端口 9101，负责后续账号、邀请码、登录、JWT 和权限身份。onboarding、exam、whitelist、attendance 会一起承接入服流程。guide、communication、server、profile、content、moment、notification、admin 分别承接指南、外部交流入口、服务器状态、成员档案、内容展示、素材投稿和审核、站内通知、后台管理。

每个业务服务都按 Java 常见分层放目录：controller 接请求，service 写业务规则，mapper 做数据库读写，entity/dto/vo/enums/config/feign/exception 放各自类型。前端以后只走 gateway-service，不直接打某个业务服务端口。

## 本地建库

PowerShell 不稳定支持 Bash 风格的 `<` 重定向，推荐这样执行建库脚本：

```powershell
$env:MYSQL_PWD="<local password>"
Get-Content -LiteralPath ".\sql\init-p0-schemas.sql" -Raw | mysql --user=root --host=127.0.0.1 --port=3306
```

脚本只创建 P0 schema，不创建业务表。现在有 `beiming_auth`、`beiming_onboarding`、`beiming_server`、`beiming_guide`、`beiming_communication`、`beiming_exam`、`beiming_whitelist`、`beiming_attendance`、`beiming_profile`、`beiming_content`、`beiming_moment`、`beiming_notification`、`beiming_admin`。

## 构建测试

```powershell
mvn test
```

只测 auth-service 的当前测试闭环：

```powershell
mvn -pl auth-service -am -Dtest=AuthTestConnectionControllerTest -DfailIfNoTests=false test
```

## 本地运行

先确认 Nacos 在 `http://127.0.0.1:8848/nacos` 可访问，再启动服务。

```powershell
mvn -pl auth-service spring-boot:run
mvn -pl gateway-service spring-boot:run
```

auth-service 自测地址是 `http://127.0.0.1:9101/api/auth/test-connection`。网关联通性地址是 `http://127.0.0.1:9000/api/auth/test-connection`。返回统一格式 `{ code, message, data }`，data 里能看到 `service=auth-service` 和 `message=gateway can reach auth-service`。

## 同伴接入 Go/React 运维模块

管理服务器容器、云盘资源、虚拟机这类能力可以作为独立 ops 微服务接入官网。推荐后端服务名用 `ops-service`，外部路径用 `/api/ops/**`，不要把运维逻辑塞进 admin-service。admin-service 更适合做后台聚合和权限入口，ops-service 负责真实的容器、云盘、虚拟机资源 API。

Go 服务只要注册到同一个 Nacos，服务名叫 `ops-service`，网关加一条 `lb://ops-service` 路由即可接进现有链路。React 前端可以放在未来 `frontend/` 工程里，通过网关请求 `/api/ops/**`。
