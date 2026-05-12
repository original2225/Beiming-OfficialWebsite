# Auth Service Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the current auth-service easier to understand, test, and extend without rewriting the existing working login/session flow.

**Architecture:** Keep the current Spring Boot 3, Java 21, JdbcTemplate, PostgreSQL, and session-token approach. Add tests first, then replace loose request/response maps with typed DTO/VO classes, then split the large service only where tests protect behavior.

**Tech Stack:** Java 21, Spring Boot 3.5.7, Spring Web, Spring JDBC, PostgreSQL, JUnit 5, Spring Boot Test.

---

### Task 1: Protect Local IDE Files

**Files:**
- Modify: `C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\.gitignore`

- [ ] **Step 1: Check current git status**

Run:

```powershell
git status --short --branch
```

Expected: `.idea/` appears as untracked.

- [ ] **Step 2: Add IDE ignore rule**

Add this line to `.gitignore` if it is not already present:

```gitignore
.idea/
```

- [ ] **Step 3: Verify .idea is ignored**

Run:

```powershell
git status --short --branch
```

Expected: `.idea/` no longer appears.

- [ ] **Step 4: Commit**

Run:

```powershell
git add .gitignore
git commit -m "chore: ignore local ide files"
```

### Task 2: Add Test Support To auth-service

**Files:**
- Modify: `C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service\pom.xml`

- [ ] **Step 1: Add Spring Boot test dependency**

Add this dependency under `<dependencies>`:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Run auth-service tests**

Run:

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service
mvn test
```

Expected: build succeeds even if there are no tests yet.

- [ ] **Step 3: Commit**

Run:

```powershell
git add backend/auth-service/pom.xml
git commit -m "test: add auth service test support"
```

### Task 3: Add Controller Smoke Tests

**Files:**
- Create: `C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service\src\test\java\dev\beiming\auth\AuthControllerTest.java`

- [ ] **Step 1: Create a MockMvc controller test**

Create this test file:

```java
package dev.beiming.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {
  @Test
  void healthReturnsAuthServiceName() throws Exception {
    var controller = new AuthController(null);
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    mockMvc.perform(get("/health"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.service").value("beiming-auth-service"));
  }
}
```

- [ ] **Step 2: Run the specific test**

Run:

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service
mvn -Dtest=AuthControllerTest test
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`.

- [ ] **Step 3: Commit**

Run:

```powershell
git add backend/auth-service/src/test/java/dev/beiming/auth/AuthControllerTest.java
git commit -m "test: add auth controller smoke test"
```

### Task 4: Introduce Typed Auth Requests

**Files:**
- Create: `C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service\src\main\java\dev\beiming\auth\RegisterRequest.java`
- Create: `C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service\src\main\java\dev\beiming\auth\LoginRequest.java`
- Modify: `C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service\src\main\java\dev\beiming\auth\AuthController.java`
- Modify: `C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service\src\main\java\dev\beiming\auth\AuthService.java`

- [ ] **Step 1: Add RegisterRequest**

```java
package dev.beiming.auth;

public record RegisterRequest(String name, String email, String password) {
}
```

- [ ] **Step 2: Add LoginRequest**

```java
package dev.beiming.auth;

public record LoginRequest(String email, String password) {
}
```

- [ ] **Step 3: Update controller method signatures**

Change register and login methods in `AuthController`:

```java
@PostMapping("/api/auth/register")
ApiEnvelope<Map<String, Object>> register(@RequestBody RegisterRequest body) {
  return ApiEnvelope.ok(auth.register(body));
}

@PostMapping("/api/auth/login")
ApiEnvelope<Map<String, Object>> login(@RequestBody LoginRequest body) {
  return ApiEnvelope.ok(auth.login(body));
}
```

- [ ] **Step 4: Update service method signatures**

Change register and login methods in `AuthService`:

```java
synchronized Map<String, Object> register(RegisterRequest body) {
  var name = string(body.name()).trim();
  var email = normalizeEmail(body.email());
  var password = string(body.password());
  ...
}

synchronized Map<String, Object> login(LoginRequest body) {
  var email = normalizeEmail(body.email());
  var password = string(body.password());
  ...
}
```

- [ ] **Step 5: Run auth-service tests**

Run:

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service
mvn test
```

Expected: tests pass.

- [ ] **Step 6: Commit**

Run:

```powershell
git add backend/auth-service/src/main/java/dev/beiming/auth/RegisterRequest.java backend/auth-service/src/main/java/dev/beiming/auth/LoginRequest.java backend/auth-service/src/main/java/dev/beiming/auth/AuthController.java backend/auth-service/src/main/java/dev/beiming/auth/AuthService.java
git commit -m "refactor: use typed auth request records"
```

### Task 5: Document Current Auth Flow

**Files:**
- Create: `C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\docs\auth-service-flow.md`

- [ ] **Step 1: Write the flow doc**

Create this file:

```markdown
# auth-service 请求流程

注册请求走 `POST /api/auth/register`。Controller 接收注册请求，调用 AuthService。AuthService 校验用户名、邮箱和密码，检查邮箱是否已注册，生成盐和密码哈希，写入 `beiming_users`，再生成 session token，数据库只保存 token hash，最后把 token 和公开用户信息返回给前端。

登录请求走 `POST /api/auth/login`。AuthService 按邮箱查用户，检查账号状态，使用同一个盐重新计算密码哈希，再用固定时间比较判断密码是否正确。成功后更新最后登录时间，并创建新的 session token。

当前用户请求走 `GET /api/auth/me`。前端带 `Authorization: Bearer <token>`。Controller 取出 token，AuthService 哈希 token 后查 `beiming_sessions` 和 `beiming_users`。查到有效 session 就返回用户信息，查不到就返回 401。

网关请求走 `backend/api-gateway`。auth 和 users 路径直接转发到 auth-service。其他 `/api/**` 先调用 `/api/auth/validate`，确认 token 有效后再转发给 resource-service。
```

- [ ] **Step 2: Commit**

Run:

```powershell
git add docs/auth-service-flow.md
git commit -m "docs: describe auth service flow"
```

### Task 6: Final Verification

**Files:**
- Read only.

- [ ] **Step 1: Run auth-service tests**

Run:

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite\backend\auth-service
mvn test
```

Expected: build succeeds and all tests pass.

- [ ] **Step 2: Run Spring services build**

Run:

```powershell
cd C:\Users\15780\Documents\GitHub\Beiming-OfficialWebsite
npm run spring:build
```

Expected: auth-service, api-gateway, and resource-service package successfully.

- [ ] **Step 3: Check git status**

Run:

```powershell
git status --short --branch
```

Expected: clean working tree.
