# app-calendar（pagination / delete）— itonami（dapp）上の分散型設計

状態: 第 1 スライス実装（2026-10-20）。正本はこの文書。
追跡: Linear JUN-128（親 JUN-116）。app-calendar の第 3 スライス。

## このスライス: ページング・削除判定コア

| 層 | 実体 | 状態 |
|---|---|---|
| 判定コア guest | `aiueos/calendar_page.cljk` — has-next-page（offset+limit < total）/ page-default（offset 0 / limit 50）/ range-judgment（**startsAfter 以上・startsBefore 未満**、無指定は無条件）/ delete-judgment（ifSequence 競合 = 412、update と同一規則） | **実装済み・実測** |
| 記述子 | `aiueos/calendar-page.edn` | **実装済み** |
| parity | `lg/test/lg_calendar/page_parity_test.cljk` — FakeCalendarStore 上の実関数 `list-events` / `delete-event` と一致（デフォルト・次ページ・窓・削除競合） | **実装済み・実測** |

実測: `amu test aiueos/calendar_page.cljk` → **24/24**（jvm-kir / js / wasm）。
parity → 2 tests / 12 assertions / 0 failures。

## バイト互換の核心（guest が判定の主）

canonical は `offset / limit / total` の三つ組だけを持ち、**Google の
`nextPageToken` / MS の `@odata.nextLink` はスキンがこの判定から組み立てる**。
次ページの有無（offset+limit < total）が両スキンの一致点。startsAt 窓は
**startsAfter 以上・startsBefore 未満**（= 互換のための非対称。実測で確認）。

## 実測メモ

- `->int` の nil は default のみ（負値や 0 limit はそのまま通る）——
  **クランプはしない**。guest もクランプしない（正本追従）。
- delete も update と同じ ifSequence 競合規則（conflict は sequence を
  触らない = 直後に notFound）。

## 未実装境界

- スキン側の token 文字列（nextPageToken / @odata.nextLink）の組み立ては
  CF Worker（TS）= host。
- rsvp の判定、orderBy の nil-last ソート判定。
- mon（DNA）化、aiueos session 載せ（fleet 共通）。
