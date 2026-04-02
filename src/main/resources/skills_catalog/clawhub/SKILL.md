---
name: clawhub
description: Use ClawHub CLI to search, install, update, and list skills in workspace.
metadata: {"requires": {"bins": ["npx"], "env": []}, "always": false}
always: false
---

# ClawHub Skill

Use this skill when the user wants to discover or install third-party skills.

## Important

- This skill requires the `exec` tool and `npx` on host.
- Install targets should stay inside the workspace skills directory.

## Standard commands

1. Search skills:

```bash
npx --yes clawhub@latest search "<query>" --limit 5
```

2. Install a skill:

```bash
npx --yes clawhub@latest install <slug> --workdir .
```

3. Update installed skills:

```bash
npx --yes clawhub@latest update --all --workdir .
```

4. List installed skills:

```bash
npx --yes clawhub@latest list --workdir .
```

## Usage notes

- Run commands via `exec`.
- After installation, refresh skills to expose the new summary.
