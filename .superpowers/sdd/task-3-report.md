# Task 3 Report

## 实现内容
- 新增 `auth` 模块，提供 `AuthService.register(RegisterRequest)` 与 `AuthService.login(LoginRequest)`。
- 新增 `JwtService`，支持 `createToken(UserEntity)`、`parseUserId(String)`，并在鉴权链路中解析当前用户。
- 新增 `CurrentUser(Long id, String username, String role)`，通过 JWT 登录态注入到 `GET /api/auth/me`。
- 新增认证接口：
  - `POST /api/auth/register`
  - `POST /api/auth/login`
  - `GET /api/auth/me`
- 新增 `users` 表对应的 `UserEntity` 与 `UserMapper`。
- 新增 `SecurityConfig`，放行 `/api/auth/register`、`/api/auth/login`，其余 `/api/**` 统一要求 JWT。
- 在 `application.yml` 与 `application-docker.yml` 增加 `security.jwt.secret` 默认配置。

## 测试结果
- `cd backend && mvn -Dtest=AuthServiceTest test`：通过，4/4。
- `cd backend && mvn test`：通过，7/7。

## TDD Evidence
### RED
- 先新增 `backend/src/test/java/com/example/myagent/auth/AuthServiceTest.java`。
- 首次执行 `mvn -Dtest=AuthServiceTest test` 时失败：
  - 初次失败原因为 Maven 网络沙箱阻止依赖解析。
  - 提权后重跑，编译失败，暴露出 bearer token 类型选型错误与 `JwtService` 签名 key 类型错误。

### GREEN
- 将 bearer token 转换与认证改为当前依赖可用的 `UsernamePasswordAuthenticationToken` 路径。
- 将 JWT 签名 key 调整为 `SecretKey`。
- 再次执行 `mvn -Dtest=AuthServiceTest test` 通过。
- 最后执行 `mvn test` 全量通过。

## 文件清单
- `backend/src/main/java/com/example/myagent/auth/AuthController.java`
- `backend/src/main/java/com/example/myagent/auth/AuthResponse.java`
- `backend/src/main/java/com/example/myagent/auth/AuthService.java`
- `backend/src/main/java/com/example/myagent/auth/CurrentUser.java`
- `backend/src/main/java/com/example/myagent/auth/JwtAuthenticationManager.java`
- `backend/src/main/java/com/example/myagent/auth/JwtService.java`
- `backend/src/main/java/com/example/myagent/auth/LoginRequest.java`
- `backend/src/main/java/com/example/myagent/auth/RegisterRequest.java`
- `backend/src/main/java/com/example/myagent/auth/ServerBearerTokenAuthenticationConverter.java`
- `backend/src/main/java/com/example/myagent/user/UserEntity.java`
- `backend/src/main/java/com/example/myagent/user/UserMapper.java`
- `backend/src/main/java/com/example/myagent/config/SecurityConfig.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-docker.yml`
- `backend/src/test/java/com/example/myagent/auth/AuthServiceTest.java`

## 自审
- 密码仅以 BCrypt hash 存储，未落明文。
- JWT 中包含 `sub=userId`、`username`、`role`。
- `/api/auth/register`、`/api/auth/login` 放行，其余 `/api/**` 需要认证。
- 变更限制在任务允许范围内，未修改 Task 1/2 无关代码。

## Concerns
## task_3_fix_report
- status: completed
- changed_files:
  - backend/src/main/java/com/example/myagent/auth/JwtAuthenticationManager.java
  - backend/src/main/java/com/example/myagent/auth/JwtService.java
  - backend/src/main/resources/application.yml
  - backend/src/main/resources/application-docker.yml
  - backend/src/test/java/com/example/myagent/auth/JwtAuthenticationManagerTest.java
  - backend/src/test/java/com/example/myagent/auth/JwtServiceTest.java
- commit: e42ea81 Fix JWT authentication failure handling
- commands_and_results:
  - `cd backend && mvn -Dtest=JwtServiceTest test` -> failed first with constructor exceptions not normalized; passed after fix (2 tests, 0 failures)
  - `cd backend && mvn -Dtest=JwtAuthenticationManagerTest test` -> passed after fix (2 tests, 0 failures)
  - `cd backend && mvn -Dtest=AuthServiceTest test` -> passed (4 tests, 0 failures)
  - `cd backend && mvn test` -> passed (11 tests, 0 failures)
- concerns:
  - Maven test output still emits ByteBuddy/JDK dynamic-agent warnings, but the suite exits 0 and there is no auth regression remaining in the covered paths.
- 当前只按 brief 强制要求补了认证服务测试，未额外补控制器/安全链集成测试。
- `displayName` 在注册时若为空会回退为 `username`，brief 未显式约束该细节，但不影响认证主流程。
