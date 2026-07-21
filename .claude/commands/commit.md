---
description: Create a Conventional Commit (feat:, fix:, ...) with a bullet body and an issue ref
argument-hint: "[issue number or scope]"
---

Create a **Conventional Commit** for the current changes.

1. Look at what changed: `git status` and `git diff` (and `git diff --cached`). If nothing is
   staged, stage the relevant files.
2. **Subject**: `type(optional-scope): short imperative summary`
   - **type** ∈ `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`
   - ≤ 72 chars, lower-case, no trailing period; use `!` for a breaking change.
3. **Body**: a few **brief bullet points** of what was done (one line each, imperative).
4. **Footer**: reference the related issue — `Refs #N` (or `Closes #N` if it fully resolves it).
   If `$ARGUMENTS` is a number, use it as the issue. Multiple issues: `Refs #1, #10`.
5. Create the commit.

**Do NOT add any attribution** — no `Co-Authored-By`, no Claude/Anthropic mention, no session link.

Example:
```
feat(strategy): add VWAP reject entry

- detect reversal when price breaks back below VWAP after a pump
- make entry threshold configurable via StrategyParams

Refs #3
```
