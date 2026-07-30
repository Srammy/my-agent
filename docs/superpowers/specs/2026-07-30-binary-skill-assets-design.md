# Binary-Safe Skill Resource Upload Design

## Context

The Skill directory upload API accepts `SKILL.md` and files below
`references/`, `scripts/`, and `assets/`. The current implementation converts
every resource from `byte[]` to a UTF-8 `String` before passing it to
`AgentSkill.resources`. Invalid UTF-8 bytes are replaced, so binary files such
as PNG images and fonts cannot be restored after upload.

AgentScope 2.0.0-RC4 models eager `AgentSkill.resources` as
`Map<String, String>`, but `WorkspaceSkillRepository` exposes lazy resources
through `SkillResources.readBinary()`. The configured `AbstractFilesystem`
already uploads and downloads raw `byte[]`, so binary support does not require
an SDK fork.

## Goals

- Preserve every uploaded resource byte exactly.
- Support binary files in all currently allowed resource roots:
  `references/`, `scripts/`, and `assets/`.
- Continue treating `SKILL.md` as UTF-8 text and reject malformed UTF-8.
- Preserve path validation, user isolation, duplicate detection, and the
  existing upload size and file-count limits.
- Avoid publishing a visible Skill until all resource files have been stored.

## Non-Goals

- Adding a Skill resource download API.
- Inferring MIME types or classifying files as text versus binary.
- Changing the allowed Skill directory roots.
- Modifying or forking AgentScope resource model classes.
- Making filesystem multi-file writes transactionally atomic.

## Design

### Validation

The service continues collecting multipart files as bounded `byte[]` values.
It requires a root `SKILL.md`, validates every non-root path through
`SkillPathValidator`, and performs all path validation before writing any
files.

`SKILL.md` is decoded with a UTF-8 decoder configured to report malformed or
unmappable input. Invalid UTF-8 returns HTTP 400. The decoded Markdown is
validated by `SkillValidator` and `SkillUtil.createFrom`. `SkillUtil` receives
an empty resource map because its resource model is text-only; the resulting
`AgentSkill` is used for metadata validation and the response DTO.

The existing `WorkspaceSkillRepository` remains responsible for listing,
duplicate detection, and deletion. A Skill is considered visible when its
`SKILL.md` exists.

### Storage Flow

1. Collect the bounded multipart files.
2. Validate `SKILL.md`, its declared Skill name, and every resource path.
3. Reject the request with HTTP 409 if the repository already contains the
   Skill.
4. Prefix each validated resource path with `skills/<skillName>/` and upload
   the original `byte[]` values through `AbstractFilesystem.uploadFiles`.
5. Verify every resource upload response succeeded. On failure, return HTTP
   500 and do not write `SKILL.md`.
6. Upload the original `SKILL.md` bytes as
   `skills/<skillName>/SKILL.md`.
7. Verify the marker upload succeeded, then return the validated Skill
   metadata.

Writing `SKILL.md` last prevents resource upload failures from creating a
Skill visible to repository listing. Failed attempts may leave bounded,
invisible resource files, and a retry can overwrite them. This avoids a
destructive rollback that could race with another request.

### Reads

`WorkspaceSkillRepository` continues loading Skill metadata from `SKILL.md`.
Resource consumers use its existing lazy `SkillResources` implementation:
`read()` for text and `readBinary()` for raw bytes. No read-path changes are
required.

### Error Handling

- Missing `SKILL.md`, invalid paths, invalid Markdown, and malformed UTF-8:
  HTTP 400.
- Duplicate Skill name: HTTP 409.
- Existing multipart count or size limits: HTTP 413.
- Any filesystem upload failure: HTTP 500 with no successful API response.

## Testing

- Upload a valid Skill containing `assets/icon.png` with bytes that are invalid
  UTF-8, then assert the filesystem contains exactly the original bytes.
- Verify textual files below `references/` and `scripts/` also retain their
  original bytes.
- Verify malformed UTF-8 in `SKILL.md` returns HTTP 400 without writing files.
- Verify an invalid resource path is rejected before any upload.
- Verify a resource upload failure does not write `SKILL.md`.
- Keep the existing list, duplicate, deletion, user-isolation, file-count, and
  upload-size tests passing.

## Success Criteria

- Binary resources round-trip byte-for-byte through the backing filesystem.
- No uploaded resource is converted through UTF-8 before storage.
- `SKILL.md` remains strictly validated text.
- A failed resource upload does not publish a visible Skill.
- The backend test suite passes.

## Final Review Discovery

AgentScope 2.0.0-RC4 `RemoteFilesystem.uploadFiles()` attempts UTF-8 storage
with `new String(bytes, UTF_8)` and only selects Base64 if that call throws.
Malformed byte sequences are replaced rather than rejected, so the fallback is
unreachable for arbitrary binary content. The project-owned
`BinarySafeRemoteFilesystem` retains the SDK's `BaseStore`, namespace,
`FileData` metadata, and response contract while selecting UTF-8 only through
a strict decoder and Base64 otherwise. Only the workspace filesystem bean uses
this adapter.

The final review also found that resource paths must reject `SKILL.md` in every
path segment position other than the required root marker, and that repository
operations must use the canonical name parsed by `SkillUtil`, not the
lightweight front-matter parser. Multipart acceptance limits remain unchanged;
the parser's in-memory threshold is 64 KiB so accepted near-limit parts spool
to disk before service-level total-size accounting.
