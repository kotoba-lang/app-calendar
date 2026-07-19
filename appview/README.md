# etzhayyim-project-calendar App migration

このディレクトリは `legacy-runtime` 実装を残したまま、App 版を段階移行するための配置先です。

## 対象 App services

- `actor-z17443d4`
- `calendar-actors-calendar-user-73e7bfip`
- `calendar-services-calendar-service-x82a28pr`
- `calendar-systems-database-1pupt6c6`
- `calendar-whu3uukx`

## App 実装方針

- 各 service は `projects/*/wasm/*-component` として順次実装。
- 既存 App runtime は互換運用のため維持。
- HTTP/cron/job エンドポイントから優先して移植。
