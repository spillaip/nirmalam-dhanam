# Manual quality-assurance matrix

Automated tests cover core calculations, backup checksum verification, starter-data scoping, and every Room migration origin. Execute this matrix before the first public Play rollout and record device model, build number, and outcome for every row.

| Area | API 26 | API 29 | API 33 | API 35 | API 36 |
| --- | --- | --- | --- | --- | --- |
| Fresh install, unlock, and passphrase errors | Required | Required | Required | Required | Required |
| Upgrade an existing v1–v9 database | Required | Required | Required | Required | Required |
| JSON/.ndf export, wrong-passphrase import, and correct restore | Required | Required | Required | Required | Required |
| Khata, Vyavahara, Varga, Vyakti, report, Nivesha, Sampada flows | Required | Required | Required | Required | Required |
| Dark mode, rotation, split-screen/tablet, neurodiverse mode | Required | Required | Required | Required | Required |
| TalkBack and 200% font scale | Required | Required | Required | Required | Required |

## Large-data scenario

On an isolated test database, create at least 1,000 Vyavahara entries, 100 Varga, 200 Vyakti, 40 Khatas, and 500 dated Nivesha check-ins. Verify search, filters, reports, portfolio history, export/import, rotation, and scroll state. Capture cold-start, first-list-render, search, and export timings; investigate any main-thread jank or data loss.

## Accessibility acceptance

With TalkBack enabled, verify every icon has a meaningful label, dropdowns announce state, dialogs trap and return focus, destructive actions state their effect, and charts have textual summaries. At 200% font scale verify text is not clipped and controls remain reachable. Verify dark mode colour contrast and reduced-stimulation behaviour in neurodiverse mode.
