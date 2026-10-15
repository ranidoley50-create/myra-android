---
name: GitHub publishing from Replit
description: GitHub source-control connection behavior and safe publishing verification for this workspace.
---

For this workspace, a GitHub account-level connection can report healthy and `addIntegration` can report success while the integration listing remains `not_added` and both HTTPS and SSH Git pushes fail. Do not keep retrying OAuth or claim a push succeeded from a configured remote alone. Replit's documented Git pane is the supported publishing path; its docs say full support is on desktop, so Android Chrome's desktop-site mode is best-effort.

**Why:** The source-control connection and Git CLI authentication did not agree during MYRA's remote APK setup.

**How to apply:** Before starting CI, confirm the Git pane pushed the local `main` branch and verify the repository has the commit/ref. Then inspect the Actions run; a local remote entry is not proof of a successful push.
