# Contributing

English | [中文](CONTRIBUTING.zh-CN.md)

Phone Agent changes should keep the local-first safety model intact. The Console can control writable terminal sessions, so UI, script, and deployment changes must not imply that unauthenticated remote exposure is safe.

## Development Setup

1. Install JDK, MySQL, Codex CLI, `tmux`, and `ttyd`.
2. Copy `.env.example` to an untracked local file and export the values you need.
3. Run `scripts/phone-agent-dev.sh status`.
4. Use `./gradlew test jacocoTestReport` for Java changes.
5. Use `node --test src/test/js/*.test.mjs` for Console JavaScript changes.

## Requirement Workflow

For non-trivial product, UI, state, security, or integration work, use the fd-v5 flow used in this repository:

- PRD and Requirement Cases.
- Technical Design and Technical Cases.
- Development loop with tests.
- Browser rendered evidence for UI changes.
- Delivery review and final audit.

Documentation-only changes can be smaller, but they still need accurate safety and dependency statements.

## Testing Expectations

- Java executable production changes need unit or contract tests and JaCoCo evidence.
- JavaScript executable Console changes need Node tests and V8 coverage or an equivalent verifier.
- UI visual claims require rendered evidence from a real browser or target runtime. DOM checks, CSS rules, or computed state are useful diagnostics but are not enough to prove visual correctness.
- Hardware-only phone flows can be documented as manually verified or unavailable when the contributor does not have the device.

## UI Changes

When changing Console UI:

- Verify desktop and mobile viewports.
- Check that text does not overlap, clip, or require hover-only meaning on mobile.
- Keep `1/2/3/4/6` pane layouts and drag/drop session behavior unless a reviewed requirement changes them.
- Keep Chinese and English visible text in sync.

## Documentation Changes

Public-facing documentation must keep language files separate:

- English default: `README.md`, `CONTRIBUTING.md`, `docs/*.md`.
- Chinese version: `README.zh-CN.md`, `CONTRIBUTING.zh-CN.md`, `docs/*.zh-CN.md`.
- Each paired document should include a language switch at the top.

## Security Changes

Do not weaken loopback and local-workspace protections. If a change needs remote access, document it as a new security design rather than expanding the default local setup.
