# app-calendar（イベント ID）— itonami（dapp）上の分散型設計

状態: 第 1 スライス実装（2026-10-20）。正本はこの文書。
追跡: Linear JUN-128（親 JUN-116）。

## 位置づけ

app-calendar は **Google Calendar v3 と Microsoft Graph の両方にバイト形で
互換な calendar API**（calendar.etzhayyim.com、canonical は kotoba datomic
`calendar-v1`）。workspace-compat プログラムのリファレンス垂直。

## このスライス: イベント ID 判定コア

| 層 | 実体 | 状態 |
|---|---|---|
| 判定コア guest | `aiueos/calendar_ids.cljk` — slug 形（6..32 文字・[0-9a-z]）/ id-class（eid → DID → bare slug の優先順）/ resolve-judgment（解決しない id は fail closed で lookup へ） | **実装済み・実測** |
| 記述子 | `aiueos/calendar-ids.edn` | **実装済み** |
| parity | `lg/test/lg_calendar/ids_parity_test.cljk` — 実関数 `resolve-slug` と一致（優先順・lookup 回し・形拒否） | **実装済み・実測** |

実測: `amu test aiueos/calendar_ids.cljk` → **21/21**（jvm-kir / js / wasm）。
parity → 9 assertions / 0 failures。

## 正本の正確な規則（guest はこれに追従）

- `resolve-slug`: 呼び出し側 id（slug / eid / DID）を bare slug に解決する。
  **provider-native 形や iCalUid 形は解決しない**（datomic lookup に回す）。
- 形: `^[0-9a-z]{6,32}$`。eid = `cal:event:{slug}`、DID =
  `did:web:calendar.etzhayyim.com:event:{slug}`、iCal UID = `{slug}@domain`。
- derived id の文字列組み立て（eid/DID/AT URI/UID）は host 仕事 —— guest は
  形と解決の判定だけを持つ（スカラー host 前提）。

## 未実装境界

- CanonicalEvent ↔ Google / Graph のスキン変換（edn/handlers の tx 射影）の
  判定化 —— 「両方にバイト互換」の核心なので次スライス候補。
- new-slug の生成（SecureRandom = host）。EDN encode/decode は文字列操作 = host。
- mon（DNA）化、aiueos session 載せ（fleet 共通）。
