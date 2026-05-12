# resource-service 下一阶段开发指南

这份文档用于新开对话继续开发 `resource-service`。先读它，再让 AI 读当前代码。当前项目已经不是旧的官网微服务骨架，而是 Beiming Console 结构。

## 当前进度

当前分支是 `feature/p0-backend-microservices`。

项目现在由 React 前端、Spring Boot API 网关、Spring Boot auth-service、Spring Boot resource-service、Go node-daemon 组成。网关默认端口是 `8787`，auth-service 默认端口是 `8792`，resource-service 默认端口是 `8791`，node-daemon 默认端口是 `8790`。

auth-service 已经有注册、登录、退出、会话校验、用户列表、用户修改和云盘相关接口。它用 PostgreSQL 保存用户和 session。它不是空壳。

node-daemon 已经实现很多真实资源操作，包括进程实例、指标、Docker 容器、镜像、容器文件、虚拟机和 WebSocket。它是运行在被管理机器上的执行器。

resource-service 目前最薄。它只有健康检查、Docker Hub 镜像搜索、节点列表、创建节点、更新节点、删除节点。节点配置保存在 `data/remote-nodes.json`，也可以从 `REMOTE_NODES_JSON` 环境变量读取。

## 推荐下一步开发 resource-service

下一步建议完成 `resource-service`，不是继续 auth-service。

原因很直接：auth-service 已经能承担登录会话，node-daemon 也已经有大量执行能力。现在缺的是 Java 控制面。也就是前端和网关应该优先打到 resource-service，由 resource-service 再按节点配置调用对应 daemon。这样才能把容器、虚拟机、文件、指标这些资源能力变成稳定的后端微服务接口。

如果现在让前端直接连 daemon，短期能跑，但权限、审计、节点 token、错误格式、跨节点聚合都会散。resource-service 做好后，它负责收口这些东西。

用 Java 大白话讲，resource-service 负责管理“有哪些服务器节点”和“我要对哪个节点做什么操作”。node-daemon 负责在那台机器上真正执行 Docker、文件、虚拟机命令。

## 第一阶段目标

第一阶段不要重写 node-daemon，也不要做复杂前端。先把 resource-service 做成资源控制面的最小闭环。

完成后应该有这些能力：能保存和读取节点配置，能测试某个节点 daemon 是否在线，能通过 resource-service 转发读取节点指标，能通过 resource-service 获取容器列表，能统一处理 daemon token，能返回统一的 `ApiEnvelope`。

## 建议接口

保留已有节点接口：

```text
GET /api/nodes
POST /api/nodes
PUT /api/nodes/{nodeId}
DELETE /api/nodes/{nodeId}
```

新增节点健康检查：

```text
GET /api/nodes/{nodeId}/health
```

新增节点指标代理：

```text
GET /api/nodes/{nodeId}/metrics
```

新增容器列表代理：

```text
GET /api/nodes/{nodeId}/containers
```

新增镜像列表代理：

```text
GET /api/nodes/{nodeId}/images
```

后续再做容器启动、停止、重启、删除、创建、文件操作和虚拟机操作。第一阶段先读数据，不先做高风险写操作。

## 建议代码结构

当前 `resource-service` 的代码都在 `dev.beiming.api` 包里，文件很少，可以先保持这个包名。

建议新增 `DaemonClient`。它专门负责调用 node-daemon。Controller 不直接拼 URL，不直接处理 Authorization header。

建议新增 `DaemonProxyService`。它负责业务语义，比如“查这个节点健康状态”“查这个节点容器列表”。它先找 `NodeService.get(nodeId)`，再调用 `DaemonClient`。

建议把请求响应先保持简单，不急着建很多 DTO。读接口可以先返回 `Object` 或 `Map<String,Object>`，等接口稳定后再细化类型。

## 零基础学习清单

先学 HTTP 代理。resource-service 收到请求后，并不是自己查 Docker，而是根据 nodeId 找到 daemonUrl，再用 HTTP 调用 node-daemon。

再学 RestClient。当前项目已经在 `RemoteController` 里用 `RestClient` 搜 Docker Hub。下一步调用 node-daemon 也可以用它。你要知道 GET 怎么写、header 怎么带、错误状态怎么处理。

再学 Service 分层。Controller 接请求，DaemonProxyService 写资源业务，DaemonClient 管远程 HTTP 调用，NodeService 管节点配置。

再学 token 处理。daemonToken 是访问 node-daemon 的凭证。对前端来说它不应该被当作普通公开数据展示。后面要考虑 `publicNodes()` 是否还应该返回明文 token。

再学测试。resource-service 适合先写单元测试验证 NodeService，再写 DaemonClient 的假 HTTP 测试。不要每次都依赖真实 Docker 或真实 daemon。

## 新对话开场提示

可以直接粘贴下面这段：

```text
请读取 AGENTS.md、README.md、backend/README.md、backend/resource-service、backend/api-gateway、backend/node-daemon/README.md 和 docs/resource-service-next-development.md。

我要继续开发 resource-service。当前项目是 Beiming Console 结构，不要套用旧的 Nacos/MySQL/JDK8 微服务骨架。resource-service 使用 Spring Boot 3.5.7、Java 21，默认端口 8791。node-daemon 是 Go 写的执行器，默认端口 8790。

目标是把 resource-service 做成资源控制面的最小闭环：节点健康检查、节点指标代理、容器列表代理、镜像列表代理，并把调用 daemon 的逻辑收进 DaemonClient。第一阶段只做读取接口，不做容器创建、删除、停止这类高风险写操作。

请先检查当前代码和 git 状态，然后给出小步实现计划。不要直接写代码。不要提交 .idea、target、日志、node_modules，不要写入真实 token 或数据库密码，不要批量删除文件。
```

## 验证方式

resource-service 构建：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\resource-service
mvn test
```

全 Spring 服务构建：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite
npm run spring:build
```

启动 resource-service：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite
npm run dev:resource
```

启动 daemon：

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite
go run .\backend\node-daemon
```

检查节点列表：

```text
http://127.0.0.1:8791/api/nodes
```

第一阶段完成后，还应该能检查：

```text
http://127.0.0.1:8791/api/nodes/amd-9950x/health
http://127.0.0.1:8791/api/nodes/amd-9950x/metrics
http://127.0.0.1:8791/api/nodes/amd-9950x/containers
http://127.0.0.1:8791/api/nodes/amd-9950x/images
```

通过网关时端口换成 `8787`，路径不变。

## 暂时不要做

暂时不要把 node-daemon 的几千行 Go 代码大拆。暂时不要做容器删除、虚拟机删除、文件删除这类高风险写接口。暂时不要把前端直接改成绕过 resource-service。也不要把项目改回旧的 Spring Cloud 多模块结构。

先把 resource-service 的只读控制面做稳，后面再扩展写操作和审计。
