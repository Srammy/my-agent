# Skill Promotion TOCTOU Guard Implementation Plan

> **Execution note:** Implement on one dedicated `codex/` feature branch in the current workspace. Do not create a worktree. Follow strict red-green-refactor TDD and merge the verified branch back into `codex/skill-review-draft-fingerprint` with `--no-ff`.

**Goal:** Ensure AgentScope can move a user Skill draft into the formal Skill directory only when the latest decision is still approved and the draft still has the exact approved fingerprint at the filesystem move boundary.

**Architecture:** Add a shared-`BaseStore` distributed draft lock, an approval-aware `AbstractFilesystem` decorator, and a final promotion guard. All AgentScope mutations below `skills/_drafts` use the same per-user lock. Approval/rejection persists its fingerprint under that lock. Standard draft-to-Skill moves re-read the latest decision and recompute the fingerprint under the same lock immediately before delegating to `RemoteFilesystem.move`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, AgentScope Java 2.0.0-RC4 `AbstractFilesystem`/`BaseStore`, Reactor, JUnit 5, AssertJ, Mockito, Maven.

## Global constraints

- Preserve user-level, cross-session Skill sharing.
- Keep `workspaceFilesystem` as the dynamic `IsolationScope.USER` Web/API view.
- Keep `UserScopedFilesystemFactory` as the fixed-user AgentScope view.
- Use the existing shared `BaseStore`; do not add a second Redis data source.
- Lock only operations affecting `skills/_drafts`, not ordinary workspace files.
- Fail closed when the lock, decision, fingerprint, or move cannot be validated.
- Do not change the review DTO, HTTP routes, decision JSON schema, frontend, or AgentScope dependency.
- Do not implement immutable snapshots or approval history.
- Leave the pre-existing untracked `.claude/` directory untouched.

## File map

- Create `backend/src/main/java/com/example/myagent/skillreview/SkillDraftLock.java` — lock contract and owned handle.
- Create `backend/src/main/java/com/example/myagent/skillreview/BaseStoreSkillDraftLock.java` — version-CAS distributed lock implementation.
- Create `backend/src/main/java/com/example/myagent/skillreview/SkillDraftLockException.java` — fail-closed acquisition/renewal failure.
- Create `backend/src/test/java/com/example/myagent/skillreview/BaseStoreSkillDraftLockTest.java` — shared-store serialization and user isolation.
- Create `backend/src/main/java/com/example/myagent/skillreview/SkillPromotionGuard.java` — final decision/hash authorization.
- Create `backend/src/main/java/com/example/myagent/skillreview/SkillApprovalGuardedFilesystem.java` — `AbstractFilesystem` decorator.
- Create `backend/src/test/java/com/example/myagent/skillreview/SkillApprovalGuardedFilesystemTest.java` — stale approval, decision, delegation, and isolation behavior.
- Modify `backend/src/main/java/com/example/myagent/config/UserScopedFilesystemFactory.java` — cache guarded fixed-user filesystems.
- Modify `backend/src/main/java/com/example/myagent/config/AgentScopeConfig.java` — expose the shared lock bean and inject the guarded factory dependencies.
- Modify `backend/src/main/java/com/example/myagent/skillreview/SkillReviewService.java` — lock fingerprint plus decision persistence.
- Modify `backend/src/test/java/com/example/myagent/config/UserScopedFilesystemFactoryTest.java` — approval-aware move and fixed-user regression tests.
- Modify `backend/src/test/java/com/example/myagent/config/AgentScopeConfigTest.java` — new constructor/bean wiring.
- Modify `backend/src/test/java/com/example/myagent/skillreview/SkillReviewServiceTest.java` — approval/rejection lock coverage.

---

## Task 1: Shared-BaseStore user draft lock

### Files

- Create `SkillDraftLock.java`
- Create `BaseStoreSkillDraftLock.java`
- Create `SkillDraftLockException.java`
- Create `BaseStoreSkillDraftLockTest.java`

### Contract

Use a small explicit lock contract:

```java
public interface SkillDraftLock {
  Handle acquire(String userId);

  interface Handle extends AutoCloseable {
    boolean renew();

    @Override
    void close();
  }
}
```

The production implementation stores one record per user under:

```text
namespace = [userId, "_skill-draft-lock"]
key       = "mutation"
value     = { ownerToken, expiresAtEpochMilli }
```

Acquisition rules:

1. Validate nonblank `userId`.
2. Read the current `StoreItem`.
3. If missing, released, or expired, attempt `putIfVersion` with expected version `0` or the current version.
4. Retry with a short delay until the acquisition deadline.
5. Throw `SkillDraftLockException` on timeout or interruption; never run the protected operation unlocked.

Handle rules:

- `renew()` uses owner-token validation and version CAS to extend the lease.
- `close()` releases only when the stored token still matches this handle.
- A stale handle must never release a lock subsequently acquired by another owner.
- Default acquisition wait is bounded; default lease is long enough for fingerprint plus directory move.
- Provide a package-private constructor with shorter durations for deterministic tests; do not add application configuration that was not requested.

### TDD steps

1. Add a test proving two `BaseStoreSkillDraftLock` objects backed by the same `InMemoryStore` cannot concurrently enter the same user's critical section.
2. Add a test proving a held lock for user A does not block user B.
3. Add a test proving acquisition times out closed while another owner retains the same user's lock.
4. Run:

```powershell
mvn -q -f backend/pom.xml -Dtest=BaseStoreSkillDraftLockTest test
```

Expected red: compilation fails because the lock types do not exist.

5. Implement the minimum lock contract, CAS acquisition, token-checked renewal, and token-checked release.
6. Run the same command again.

Expected green: all lock tests pass without Redis or Docker.

7. Run `git diff --check`, then commit only Task 1 files:

```text
feat: add distributed skill draft lock
```

---

## Task 2: Guard the actual promotion move

### Files

- Create `SkillPromotionGuard.java`
- Create `SkillApprovalGuardedFilesystem.java`
- Create `SkillApprovalGuardedFilesystemTest.java`

### Final authorization

`SkillPromotionGuard` receives the `SkillReviewDecisionStore`. Its promotion method receives the fixed `userId`, Skill name, fixed-user delegate filesystem, runtime context, held lock handle, and the final move action.

It must process in this exact order:

1. Read `decisionStore.find(skillName, userId)`.
2. Require `status == "APPROVED"` and non-null `draftHash`.
3. Compute the live fingerprint with `new SkillDraftFingerprint(delegate)` while the caller still holds the draft lock.
4. Require equality with the stored hash.
5. Renew the lock immediately before the move; abort if renewal fails.
6. Invoke the supplied delegate move only after all checks pass.

Return `WriteResult.fail(...)` for missing/rejected/legacy/stale/unreadable/unowned cases. Do not throw an approval result or modify the decision.

### Filesystem decorator

`SkillApprovalGuardedFilesystem` implements every `AbstractFilesystem` method and delegates to a fixed-user `RemoteFilesystem`:

- read-only operations delegate directly;
- `write`, `edit`, `delete`, and `uploadFiles` acquire the user lock only when a normalized path equals `skills/_drafts` or begins with `skills/_drafts/`;
- `move` acquires the user lock when either source or target affects the draft tree;
- a move exactly matching `skills/_drafts/<name> -> skills/<name>` routes through `SkillPromotionGuard`;
- all other moves delegate unchanged inside the lock when they affect drafts;
- ordinary paths never acquire the draft lock.

Normalize leading slashes and backslashes locally. Validate the direct Skill name with `SkillPathValidator`. Do not change the global AgentScope path contract.

### TDD steps

1. Add a failing test with an actual `InMemoryStore`:
   - write a draft;
   - approve its current hash;
   - verify `WebApprovalGate` would approve it;
   - modify the draft;
   - call the guarded standard move;
   - assert the move fails, the draft remains, and no formal Skill exists.
2. Add a test proving an unchanged approved draft moves successfully.
3. Add tests proving missing, rejected, legacy, and cross-user decisions cannot authorize the move.
4. Add delegation tests proving normal workspace writes do not acquire the draft lock and draft mutations do.
5. Run:

```powershell
mvn -q -f backend/pom.xml -Dtest=SkillApprovalGuardedFilesystemTest test
```

Expected red: compilation fails because the guard and decorator do not exist.

6. Implement the guard and decorator with the smallest necessary forwarding code.
7. Run the focused test again.

Expected green: stale content never reaches `skills/<name>`, while the exact approved content does.

8. Run the lock and decorator tests together, then commit only Task 2 files:

```text
fix: guard skill promotion at filesystem move
```

---

## Task 3: Wire the guard and lock review decisions

### Files

- Modify `UserScopedFilesystemFactory.java`
- Modify `AgentScopeConfig.java`
- Modify `SkillReviewService.java`
- Modify `UserScopedFilesystemFactoryTest.java`
- Modify `AgentScopeConfigTest.java`
- Modify `SkillReviewServiceTest.java`

### Wiring

Add one `SkillDraftLock` Bean backed by the already shared `workspaceBaseStore`:

```java
@Bean
SkillDraftLock skillDraftLock(BaseStore workspaceBaseStore) {
  return new BaseStoreSkillDraftLock(workspaceBaseStore);
}
```

Construct `UserScopedFilesystemFactory` with:

- the shared `BaseStore`;
- the shared `SkillDraftLock`;
- a `SkillPromotionGuard` using the existing `SkillReviewDecisionStore`.

For each cached user, create a fixed `RemoteFilesystem(store, List.of(userId))` and wrap it with `SkillApprovalGuardedFilesystem`. Keep the existing per-user filesystem and `SkillUsageStore` caches.

### Approval/rejection critical section

Inject `SkillDraftLock` into `SkillReviewService`. For both `approve` and `reject`:

```text
acquire(userId)
  -> compute current draft hash
  -> persist the decision with that hash
  -> close the handle
```

Keep list behavior unchanged. Existing `requireDraftHash` HTTP error mapping remains unchanged.

### TDD steps

1. Update `UserScopedFilesystemFactoryTest` so the existing empty-context move first persists an approval for Alice's current hash.
2. Add a factory regression test proving the cached object is the guarded filesystem and user/session isolation remains unchanged through behavior, not implementation reflection.
3. Add service tests verifying approval and rejection acquire the requested user's lock, compute/persist inside it, and always close the handle.
4. Update all direct factory/service construction in tests to supply the new dependencies.
5. Run:

```powershell
mvn -q -f backend/pom.xml -Dtest=UserScopedFilesystemFactoryTest,SkillReviewServiceTest,AgentScopeConfigTest test
```

Expected red: constructor and bean wiring compilation failures, followed by the unapproved move regression failure.

6. Implement factory, Spring bean, and service critical-section changes.
7. Run the same focused tests until green.
8. Run all Skill-focused tests:

```powershell
mvn -q -f backend/pom.xml -Dtest=BaseStoreSkillDraftLockTest,SkillApprovalGuardedFilesystemTest,UserScopedFilesystemFactoryTest,SkillDraftFingerprintTest,SkillReviewDecisionStoreTest,SkillReviewServiceTest,WebApprovalGateTest,AgentScopeWorkspaceServiceTest,SkillControllerTest,AgentScopeConfigTest test
```

9. Commit only Task 3 files:

```text
fix: serialize skill decisions with promotion
```

---

## Task 4: Branch verification and integration

1. Review the complete feature diff against `codex/skill-review-draft-fingerprint`. Every changed production line must trace to locking draft mutations, final promotion authorization, or dependency wiring.
2. Run:

```powershell
git diff --check codex/skill-review-draft-fingerprint...HEAD
mvn -q -f backend/pom.xml clean test
```

Expected: no whitespace errors; Maven exits `0`; all suites report zero failures and errors.

3. Confirm `git status --short --branch` contains only the feature branch plus the pre-existing untracked `.claude/`.
4. Switch back to `codex/skill-review-draft-fingerprint`.
5. Merge with:

```powershell
git merge --no-ff codex/fix-skill-promotion-toctou -m "merge: guard skill promotion transaction"
```

6. Run the full clean test suite again on the merged integration branch.
7. Aggregate Surefire XML totals and confirm zero failures/errors/skips unless a pre-existing test explicitly skips.
8. Delete `codex/fix-skill-promotion-toctou` only after Git confirms it is fully merged.

## Success criteria

- A red test reproduces modification after Gate approval and before move.
- The guarded move refuses stale, rejected, missing, legacy, or cross-user approval.
- Exact approved content moves successfully through `RuntimeContext.empty()` in the bound user namespace.
- Shared-BaseStore lock instances serialize the same user's draft mutations across application instances.
- Approval/rejection fingerprint and persistence share the same user lock as promotion.
- Ordinary workspace operations do not use the draft lock.
- Full backend tests pass before and after merge.
- `.claude/` remains untouched and unstaged.
