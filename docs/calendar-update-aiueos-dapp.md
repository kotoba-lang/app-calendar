# app-calendar（更新判定）— itonami（dapp）上の分散型設計

状態: 第 1 スライス実装（2026-10-20）。正本はこの文書。
追跡: Linear JUN-128（親 JUN-116）。第 2 スライス（第 1 はイベント ID）。

## このスライス: 更新の競合・パッチ・シーケンス判定コア

| 層 | 実体 | 状態 |
|---|---|---|
| 判定コア guest | `aiueos/calendar_update.cljk` — update-judgment（**ifSequence 競合** = ETag/If-Match の中身: 不一致なら conflict = 412、無しは競合判定しない / not-found）/ patch-admissible（present かつ some のみ）/ next-sequence（inc）/ default-of（create のデフォルト: allDay=false / visibility=private / status=confirmed / sequence=0 / calendarId=primary） | **実装済み・実測** |
| 記述子 | `aiueos/calendar-update.edn` | **実装済み** |
| parity | `lg/test/lg_calendar/update_parity_test.cljk` — **FakeCalendarStore 上の実関数 create-event / update-event** と一致（conflict / notFound / sequence inc — conflict は inc しない） | **実装済み・実測** |

実測: `amu test aiueos/calendar_update.cljk` → **24/24**（jvm-kir / js / wasm）。
parity → 5 assertions / 0 failures（実関数 end-to-end）。

## parity を実行可能にした shims（`lg/test-shims/`、撤去条件つき）

kbb（sci）は JVM 実装を含む ns をロードできない。parity 実行のみ、
クラスパス `test-shims:src:test` の**先頭**に次の最小 shim を置く:

- `cheshire/core.cljk` — JSON.stringify/parse（generate-string / parse-string のみ）
- `lg_calendar/edn.cljk` — tx-op の形（[:db/add e a v] 等）と parse-edn-value の
  意味論を忠実に再現（本番の StringBuilder 実装の代替）
- `lg_calendar/kotoba_datomic.cljk` — **wire client = host 仕事**なのでスタブ
  （FakeCalendarStore の parity では呼ばれない）

実行: `kbb --backend sci --classpath test-shims:src:test -e "(require ...)"`。
**shim は判定対象（handlers の更新規則）を置き換えていない** —— mapping /
handlers / store / ids はすべて実物。本番ビルドは shim を classpath に載せない。

## 正本の正確な規則（guest はこれに追従）

- `update-event`: ifSequence が与えられ stored `cal/sequence` と違えば
  `{"ok" false "conflict" true}`（= ETag 不一致 412）。not found は notFound。
- パッチは present かつ some のフィールドを白名单からだけ取り、**成功時のみ**
  `sequence` を inc（conflict・notFound は inc しない）。
- create のデフォルト: `sequence 0` / `status "confirmed"` / `visibility`
  `"private"` / `allDay false` / `calendarId "primary"`（mapping/defaults と同値）。

## 未実装境界

- スキン変換そのもの（Google `nextPageToken` / MS `@odata.nextLink` /
  `@odata.etag` のバイト互換 reshape）は CF Worker（TS）側。pagination の
  判定化（offset/limit/total ↔ 両スキン）は次スライス候補。
- rsvp / delete の判定（delete も If-Match 412）。
- mon（DNA）化、aiueos session 載せ（fleet 共通）。
