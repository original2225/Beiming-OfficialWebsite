# auth-service 第二阶段进度记录

更新时间：2026-05-12

本轮目标是按 `.local-docs/auth-service-phase2-development-manual.md` 做结构整理，不改变现有外部接口、认证方式和 JSON 字段。

## 已完成

- Controller 请求体从 `Map<String, Object>` 切换为明确 request record：`RegisterRequest`、`LoginRequest`、`ChangePasswordRequest`、`CreateInviteCodeRequest`、`UpdateUserRequest`。
- 登录、用户和邀请码响应切换为明确 response record：`LoginResponse`、`PublicUserView`、`InviteCodeView`。
- 角色、用户状态、邀请码状态和邀请码类型收敛为枚举：`UserRole`、`UserStatus`、`InviteCodeStatus`、`InviteCodeType`。
- 密码哈希、随机盐和密码匹配迁出到 `PasswordService`，并增加 `PasswordServiceTest`。
- session token 创建、token hash、当前用户解析、登出、踢出 session 和过期 session 清理迁出到 `SessionService`，并增加 `SessionServiceTest`。
- 邀请码创建、列表、禁用、消费和使用记录迁出到 `InviteCodeService`，并增加 `InviteCodeServiceTest`。
- `ApiExceptionHandler` 增加不支持 HTTP 方法的 405 JSON 响应，避免错误方法落成 500。
- `AuthControllerIntegrationTest` 增加 405 场景，继续覆盖注册、登录、邀请码、用户权限和 session 撤销。

## 保持不变

- 仍使用 session token，不切 JWT。
- 仍使用 PostgreSQL 生产配置和 H2 PostgreSQL 兼容模式测试。
- 外部接口路径不变，响应 envelope 和字段名不变。
- 第一个用户仍自动成为 `SUPER_ADMIN`，后续注册仍必须走邀请码。
- `SUPER_ADMIN`、`ADMIN`、`MEMBER` 的权限边界不变。

## 下一步

`AuthService` 现在已经变薄，但仍承担用户注册、登录、用户列表、用户修改、改密码和旧用户迁移。下一轮适合继续拆 `UserAccountService`，再评估是否进一步拆 `UserRepository`、`SessionRepository` 和 `InviteCodeRepository`。
