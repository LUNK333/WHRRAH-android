# WHRRAH Android

Native Android counterpart to desktop WHRRAH (https://github.com/LUNK333/WHRRAH).
Scope and architecture: `docs/design-doc.md`. Build/rebuild notes: `README.md`.

## Work is split across PCs (desk + laptop) and many sessions

- Task board: `docs/TASKS.md`. Read it at the start of every session.
- Start of session: `git pull`, then check which tasks are `DOING` and by whom.
  Don't work on a task another machine has claimed unless the user says to.
- End of session (or when the user says to stop): update the task's status in
  `docs/TASKS.md`, add one line to its **Session log**, then offer to commit + push.
- Keep context in the repo (TASKS.md, docs/), not in Claude memory — memory is
  per machine and the other PC won't see it.

## Related folders (siblings of this repo)

- `../WHRRAH/` — desktop app. `../WHRRAH/wifi_gyro_sync/HANDOFF.md` has the
  proven DJI WiFi gyro protocol + decoder to port (task B1).
- `../libxrk/` — required for rebuilding `libxrk_ffi.so`.
- `../osmosis/`, `../OsmoOffload/` — DJI BLE/WiFi protocol references.

Test videos / `.xrk` files are not in git; ask the user where they are on this machine.
