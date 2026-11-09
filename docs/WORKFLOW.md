# Development workflow

Git Flow, with one rule on top: **no branch exists without an issue.**

## Branches

| Branch | Created from | Merged into | Purpose |
|---|---|---|---|
| Production (`main` or `master`) | n/a | n/a | Released code only; every merge is tagged |
| `develop` | Production | n/a | Integration of finished work |
| `feature/<issue>-<slug>` | `develop` | `develop` | New behaviour |
| `bugfix/<issue>-<slug>` | `develop` | `develop` | A defect not yet released |
| `chore/<issue>-<slug>` | `develop` | `develop` | Build, CI, docs |
| `spike/<issue>-<slug>` | `develop` | Not merged | A time-boxed question; the finding is posted on the issue |
| `release/<version>` | `develop` | Production, then `develop` | Stabilisation only, no new behaviour |
| `hotfix/<issue>-<slug>` | Production | Production, then `develop` | A defect or false statement already public |

## Life of a change

1. **Issue.** It states the goal, the checkable done criteria and its dependencies, and belongs to a milestone.
2. **Branch.** Created from the base in the table, named with the issue number, once its dependencies are merged.
3. **Commits.** Conventional Commits, for example `feat(gate): reject citations under three words (#5)`.
4. **Pull request.** Body contains `Closes #<issue>` and the evidence for each done criterion.
5. **Checks.** CI is green and the change has been reviewed against the done criteria.
6. **Merge.** `--no-ff`, so each issue remains one visible unit in the history. Delete the branch.
7. **Close.** Close the issue explicitly after merging into `develop`, because GitHub's automatic closing only
   fires on the default branch, which here is production.

## Releases

1. Branch `release/x.y.z` from `develop` when a milestone's issues are closed.
2. Only version bumps, documentation and fixes for release defects are allowed there.
3. Open a pull request to production, merge with `--no-ff`, and tag `vx.y.z`.
4. Merge the release back into `develop` and delete the branch.

Versions follow SemVer. Before 1.0.0, a milestone that changes the API raises the minor version.

## Labels

`feature`, `bug`, `hotfix`, `spike`, `chore`. The issue's type decides its label and its branch prefix.

## Opening the backlog

`docs/backlog.json` is the source for the issues. Without `--apply`, the script only validates and prints.
It skips any issue whose title already exists, so it is safe to run again.

```bash
python ../evidence-gate/scripts/open_issues.py docs/backlog.json
python ../evidence-gate/scripts/open_issues.py docs/backlog.json --apply
```
