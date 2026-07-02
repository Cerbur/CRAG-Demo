# CRAG Web Console UI Baseline v4

## Status

This directory is the approved desktop visual baseline for `plan_22`, captured on 2026-07-02 from Google Stitch project `1781302515522825622`.

Approval applies to visual language, layout density, navigation composition, typography, spacing, colors, tables, forms, status treatment and chat composition. Functional behavior and displayed data must follow the contract precedence below.

## Contract Precedence

When artifacts disagree, implementation must use this order:

1. `docs/api/console-api.openapi.yaml` and `docs/api/open-api.openapi.yaml`
2. `docs/product/web-console-stitch-prd.md`
3. This manifest's corrections
4. PNG visual snapshot
5. Stitch reference HTML

Reference HTML uses Tailwind CDN and generated markup. It is design evidence only and must not be copied into the React + Ant Design application.

## Assets

| Page | Screenshot | Reference HTML | Approval |
| --- | --- | --- | --- |
| Login | `login-desktop.png` | `login-desktop.reference.html` | Approved |
| Knowledge list | `knowledge-list-desktop.png` | `knowledge-list-desktop.reference.html` | Approved |
| Knowledge Documents | `knowledge-documents-desktop.png` | `knowledge-documents-desktop.reference.html` | Approved |
| API Keys | `api-keys-desktop.png` | `api-keys-desktop.reference.html` | Approved with mandatory corrections |
| Chat initial | `chat-initial-desktop.png` | `chat-initial-desktop.reference.html` | Approved |
| Chat active | `chat-active-desktop.png` | `chat-active-desktop.reference.html` | Approved with mandatory corrections |
| Design system | `DESIGN.md` | — | Approved token source |

## Mandatory Corrections

### Global

- Do not add notification, settings, analytics, system-health or unsupported account controls, even where an older screenshot contains them.
- Account menu only exposes supported session actions such as logout.
- Mobile layouts are derived from `DESIGN.md`: drawer navigation, 12px page margin and structured lists instead of horizontally scrolling core tables.

### Knowledge

- List fields are only Name, Created At, API Key Ready and View.
- Do not show file counts, storage, token totals, request totals, health, Edit or Delete.
- Pagination uses `pageToken` with Previous/Next semantics, not numbered total pages.

### Documents

- Accept one UTF-8 `.txt` or `.md` file, maximum 10 MiB.
- Fields are Filename, Status, Attempt, Updated At and Actions.
- Retry appears only for `FAILED && retryable`; no delete, sync, token count or chunking controls.

### API Keys

- Every key belongs to exactly one KnowledgeBase. Remove any `All Access` row or label.
- ACTIVE actions: Disable, Rotate, Revoke. DISABLED: Enable, Revoke. REVOKED: none.
- Create and rotate must open an Ant Design modal titled `Save your API key`: masked complete key, reveal, copy, one-time warning, `I have saved this key` checkbox, and disabled Done button until acknowledged.
- The modal requirement comes from the PRD even though the generated PNG failed to render the requested overlay.

### Chat

- No model selector, KnowledgeBase selector, attachment, online-system claim or keyboard-shortcut teaching text.
- API key remains in page memory only and is cleared on refresh.
- Sources render a compact three-column/list structure with visible labels `Reference`, `Document ID`, `Excerpt`; Document ID uses monospace.
- Remove `Press Enter to send` from the active screenshot and do not make sources clickable files.
- Query failures are retained with explicit Retry; no automatic retry.

## Missing States

Registration, mobile views, loading, empty, authorization failure, API failure, no retrieval results, revoke confirmation and responsive modal variants were not reliably produced by Stitch. Implement them with Ant Design using the approved tokens and the behavior defined by the PRD; their absence is not permission to omit them.
