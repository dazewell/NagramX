# Upstream traps

Non-obvious behaviour in base-fork code that has already bitten someone.
What the trap is, where it lives, and what it costs if you miss it.
Re-verify the citation before relying on it — see the README.

## SectionsScrollView sectioning differs from RecyclerListView defaults

`SectionsScrollView.isSectionView(...)` excludes only views tagged
`RecyclerListView.TAG_NOT_SECTION` plus `TextInfoPrivacyCell`,
`ShadowSectionCell`, and `HintInnerCell` (`SectionsScrollView.java:36-41`).
Unlike `RecyclerListView`'s section checker, it does **not** auto-exclude
`CollapseTextCell` or `GraySectionCell`
(`RecyclerListView.java:3289`). So disclosure/header rows that should stay on
gray must be explicitly tagged `TAG_NOT_SECTION`; otherwise they get pulled
into white rounded cards.

Card width also comes from the first child in a section run: background bounds
use `from` child X/width (`SectionsScrollView.java:163-168`). If the first
section member has horizontal margins (for example a caption row with 21dp
insets), that narrower width becomes the whole card width. Margined captions
belong outside the card run (tagged out, or moved outside the grouped container)
to keep card edges aligned.

There is a known neighbour-visibility gap in `clipChild(...)`, but it is
**bounded and cosmetic-only** — it cannot produce a full-card clip. Child
gathering used for backgrounds skips `GONE` children
(`SectionsScrollView.java:107`), but `clipChild`'s own prev/next lookup reads
`contentView`'s full child list by raw index with no visibility gate
(`SectionsScrollView.java:190-194`). That gap does not touch the clip
rectangle itself: `AndroidUtilities.rectTmp` is computed once
(`SectionsScrollView.java:196-201`) and is byte-identical across all three
branches `clipChild` can take with it (`SectionsScrollView.java:202-219`).
`prev`/`next` decide only whether to skip clipping entirely
(the early return at `:205`) or which corners get rounded — bounded by the
16dp `sectionRadius`. A hidden neighbour also does not reliably push toward
MORE clipping: `isSectionView` (`:36-41`) returns `true` for a `GONE` view, so
the missing gate pushes `prev`/`next` MORE often true, which fires the no-clip
early return (`:205`) MORE often — but that early return can itself leave
either rounded corners where square ones were expected, or vice versa,
depending on which of the three branches (`:207-219`) would otherwise have
run. Don't assert a direction; the safe, established claim is only that the
effect is bounded to corner treatment and cannot erase a child.

*(Established 2026-09-07; corrected 2026-09-09, `#eventschedule`, after a
near-total vertical clip bug — a different mechanism entirely, below — was
first suspected to be this same gap and proven not to be.)*

## SectionsLinearLayout replays a stale clip after its parent SectionsScrollView resizes

`SectionsLinearLayout.drawChild` clips each child via the parent
`SectionsScrollView.clipChild(...)` and records that `canvas.clipPath(...)`
into the LinearLayout's OWN display list (`SectionsScrollView.java:228-235`
calling into `:185-220`) — but the clip rectangle's operands are the PARENT
`SectionsScrollView`'s `getScrollY()`/`getHeight()`
(`SectionsScrollView.java:196-200`). `onScrollChanged` already invalidates
both the scroll view and `contentView` when scroll changes
(`SectionsScrollView.java:79-87`) — that idiom covered the scroll operand but,
until this fix, nothing invalidated `contentView` when the PARENT's SIZE
changed instead. **This only bites when the content view's OWN frame stays
unchanged across that resize** — the scroll view has `isFillViewport = true`
(`BottomBuilder.kt:60`), so content SHORTER than the viewport is stretched to
fill it and gets dirtied normally by that layout pass; the hazard is specific
to content TALLER than the keyboard-open viewport, which fillViewport leaves
alone. Fixed by adding the missing half of the same idiom in an
`onSizeChanged` override (`SectionsScrollView.java:90-100`).

Observable signature, so a future investigation recognises it fast: the
card's white background is painted full-height (fresh, from the ScrollView's
own re-recorded `dispatchDraw`/`drawSectionsBackgrounds`) while the card's
CONTENTS are cut mid-row (stale, from the child's cached clip). The
draw-path trace above — the stale display list, the operands it was
recorded with, the missing invalidation — is what actually establishes the
diagnosis, independent of any touch. Separately, interacting with the
affected area was OBSERVED to repair it; that observation is consistent with
the diagnosis but doesn't itself prove no layout occurred, since the SeekBar
this card holds runs its own delegate on drag that updates a label and can
invalidate or re-lay-out itself (`EventScheduleHelper.java:1003-1012`) — a
generic "any touch invalidates the child" claim is not established. Confirmed
trigger: the Early-send bottom sheet's Delay card
(`EventScheduleHelper.java:966-1033`, reached via the only `sections=true`
`BottomBuilder` consumer in the tree, `EventScheduleHelper.java:656`) clipped
almost fully invisible after the IME hid and resized the sheet — the resize
mode is declared at `BottomSheet.java:218`, measured at `:591-657`, and
applied at `:1474-1476` and `:1570-1572` — while two pattern rows had pushed
the card below the keyboard-open viewport bottom.

*(Established 2026-09-09, `#eventschedule`.)*

## clipChild has no inverted-rect guard, unlike its background-drawing sibling

`drawSectionBackground` guards against an inverted `rectTmp` before using it
(`SectionsScrollView.java:169`: `if (rectTmp.bottom < rectTmp.top) return;`),
but `clipChild` builds the same kind of rect and hands it straight to
`Path.addRoundRect`/`canvas.clipPath` with no equivalent check
(`SectionsScrollView.java:196-219`). An inverted
rect there yields an empty `Path`, and clipping to an empty path erases the
child entirely rather than just mis-rounding a corner. Not the mechanism
behind the near-total-clip bug above (that one traced to a stale cached
clip, not an inverted rect computed at draw time) and not fixed here — scoped
out of that change by the reviewing architect as a separate, pre-existing
hazard.

*(Established 2026-09-09, `#eventschedule`; pre-existing and not fixed in
this branch.)*

## An app can never change an existing notification channel's importance in code; only the user can, via system settings

`NotificationCoverController.ensureChannel(...)` still no-ops when the channel
id already exists (`NotificationCoverController.java:639-651`), because
Android treats re-creating an existing `NotificationChannel` id as a silent
no-op. There is no app-side API to raise or lower that existing channel's
importance in place - only the user can do that in Android notification
settings.

That is why the cover path still keeps two channel tiers per persona instead
of one mutable tier: `childChannelId(...)` (silent/low) and
`alertChannelId(...)` (alert/default+vibration)
(`NotificationCoverController.java:655-715`). The behavior change moved the
choice source, not the channel model: `postChild(...)` now routes between those
two existing tiers from upstream's silent signal (`silent` parameter) rather
than from a per-chat fork toggle (`NotificationCoverController.java:719-784`).
`postPreview(...)` is user-initiated and always uses the alert tier
(`NotificationCoverController.java:836-885`), while `NaxCoverAlertChannelName`
remains the alert-tier label in settings (`strings_nax.xml`).

*(Established 2026-09-07, `#disguise-alerting`.)*

## `GROUP_ALERT_SUMMARY` still mutes grouped children; non-silent covered events must skip it

`useSummaryNotification` is still the same gate in
`NotificationsController.showExtraNotifications(...)`
(`NotificationsController.java:4993`): grouped notifications are common once
multiple dialogs are pending. In that state, `GROUP_ALERT_SUMMARY` still mutes
the child regardless of the child's own channel importance. The new cover path
therefore applies `setGroupAlertBehavior(GROUP_ALERT_SUMMARY)` only when the
upstream signal is silent, and skips it when upstream says the event is
non-silent (`NotificationCoverController.java:761-765`). If that line is
applied unconditionally in the non-silent branch, alert-tier covered children
become inert as soon as grouping turns on.

*(Established 2026-09-07, `#disguise-alerting`.)*

## `isSilent` at `showExtraNotifications(...)` is rebuild-scoped, not a per-dialog covered flag

The `isSilent` argument passed into `showExtraNotifications(...)` is the
upstream rebuild-level suppression result (`notifyDisabled`) from
`showOrUpdateNotification(...)`, not a per-dialog covered-message property
(`NotificationsController.java:4505`, `:4912`, `:4987`). Reusing it unchanged
for every covered child can spread the newest dialog's suppression state across
unrelated covered dialogs; ignoring it entirely can re-enable suppressed rebuild
paths.

Current fix splits scopes explicitly: preflight captures a rebuild-wide flag
(`naxRebuildSuppressed`) and a read-only per-covered-dialog suppression map
(`naxCoverSuppressed` via `naxCoveredDialogSuppressed(...)`), then fanout composes
`coverSilent = naxRebuildSuppressed || naxDialogSuppressed == null || naxDialogSuppressed || (dialogId == lastDialogId && isSilent)`
(`NotificationsController.java:4193-4202`, `:5116-5117`, `:6001-6029`). This keeps fail-closed
behavior for missing map entries and reuses upstream's method-level suppression
for the one `lastDialogId` it actually describes, without mutating
`smartNotificationsDialogs`.

One more trap exists after that composition: a rebuild can repost the same
represented covered members again. Using token snapshots as membership state is
wrong here: they are capped at `SUPPRESSION_LIMIT` by `buildRecord(...)`
(`NotificationCoverController.java:972-995`) and therefore cannot represent an
exact membership baseline for growth decisions.

Current fix splits those roles. Exact per-dialog active membership is stored on
its own key namespace (`KEY_ACTIVE_CHILD_MEMBERS`) with an explicit
`over_capacity` sentinel for `displayCount > SUPPRESSION_LIMIT`, while token
snapshots stay interaction payload only (`NotificationCoverController.java:65`,
`:561-562`, `:1085-1134`). Child alert tier is then gated by
`effectiveSilent = upstreamSilent || migration || overCapacity || !hasGrowth` in
`postChild(...)` (`NotificationCoverController.java:758-778`): unchanged reposts
force silent, growth can alert only when upstream also allows it.

Migration trap: branches without a stored membership key but with an already
live cover token must not infer growth. That case is forced silent once and
seeds baseline only after successful post + CAS (`NotificationCoverController.java:767-770`,
`:803-811`).

Ordering trap: token records are written before `notify(...)`, but membership
baseline is written after `notify(...)` and only when active tap pointer still
matches this post token (CAS), so a concurrent interaction or newer post cannot
be overwritten by stale baseline state (`NotificationCoverController.java:767-776`,
`:803-811`).

Cleanup trap: membership key lifecycle is owned by
`clearDialogInteractionState(...)`; reconcile must scan membership-prefix keys
or orphan baselines can survive after a dialog no longer posts a cover
(`NotificationCoverController.java:1278-1282`, `:1341-1343`, `:1356`).

Android can drop notifications at global count limits without throwing from
`notify(...)`. This path therefore only treats Java exceptions as hard post
failures; absence of an exception is not a proof that Android displayed the new
card.

*(Established 2026-09-07, `#disguise-alerting`.)*

## Pre-existing (out of scope): mixed covered + uncovered batches can inherit the silent cover-summary alert behavior

This branch still has a pre-existing mixed-batch trap that this change does not
fix. When any covered dialog is present, `showExtraNotifications(...)` posts
the fork cover summary instead of the real summary (`NotificationsController.java:5016-5030`),
and that cover summary is always built on the low/silent cover-summary channel
(`NotificationCoverController.buildCoverSummary(...)` ->
`summaryChannelId(...)` -> `ensureChannel(...)`,
`NotificationCoverController.java:639-651, 667-675, 785-835`).

At the same time, non-covered child notifications in a grouped batch still set
`GROUP_ALERT_SUMMARY` in the regular child path
(`NotificationsController.java:5830`). So in a mixed covered/uncovered
grouped batch, an uncovered child that would otherwise alert can be muted by
the grouped-summary policy it shares with the silent cover summary. This is
recorded as pre-existing and out of scope for this tuning slice.

*(Established 2026-09-07, pre-existing and not fixed in this branch.)*

## Editing `TMessagesProj/build.gradle` fails Sync guard check until its blob pin is bumped

Any fork-authored PR that edits `TMessagesProj/build.gradle` — even far from
the signing block, anywhere in the file — fails the `Sync guard check`
workflow with `signing-config build.gradle blob changed: <blob>`, because that
file is blob-pinned whole, not diffed at the hunk level. `Test-SignerBlobs`
compares the candidate's git blob hash for the whole file against a single
recorded value and fails on any mismatch (`.github/sync/sync-guard.ps1:303-308`),
and the pin it checks against lives in `.github/sync/pins.env` as the
`SIGNING_GRADLE_PATH`/`SIGNING_GRADLE_BLOB` pair (guard 13, signing identity).
`GRADLE_SURFACE` in the same file documents `build.gradle` as membership-only
for every other executable-surface file, but `pins.env`'s own comment on that
line says `build.gradle` is the one exception, blob-pinned instead of just
tracked — easy to miss because it reads like the opposite of a warning.

The fix is a second, separate commit in the same PR: recompute the blob with
`git rev-parse HEAD:TMessagesProj/build.gradle` at the final tree (never
hand-copy a hash out of a CI log) and update `SIGNING_GRADLE_BLOB` in
`pins.env` to match. There's direct precedent for this exact shape of commit:
`da79971452` ("repin signing gradle blob for the icon-comment edit
#dazegram-icons"), a one-line `pins.env` bump in its own commit, done after an
unrelated `build.gradle` comment edit tripped the same guard.

*(Established 2026-09-05.)*

## `NaConfig.notificationIcon` is a persisted index into an upstream-owned enumeration that has already diverged

The setting and `getNotificationIconResId()` came from upstream Nagram commit
`bc0f99fb0b` ("feat: add option of changing notification icon", Revincx,
2023-01-19 — `git show bc0f99fb0b --stat` shows it touching
`NotificationsController.java`, `NekoGeneralSettingsActivity.java`, and
`NaConfig.kt`). That commit introduced `getNotificationIconResId()` as
`private` and non-static, with 3 cases (`case 0` → `offical_notification`,
`case 1` → `nagram_notification`, `case 2` → `notification`, per that
commit's diff). The method's signature never diverged — this fork's version
is still `private int` (`NotificationsController.java:6531-6544`) — only its
value domain did. Upstream's three cases were `0` → `offical_notification`,
`1` → `nagram_notification`, `2` → `notification`, defaulting to
`offical_notification`. This fork's four are `0` → `notification`, `1` →
`nagramx_notification`, `2` → `nagram_notification`, `3` → `neko_notification`,
defaulting to `notification`. The divergence is therefore not a uniform
shift: `offical_notification` left the domain entirely; upstream's `case 2`
asset (`notification`) became this fork's `case 0` and its default; only
upstream's `case 1` asset (`nagram_notification`) moved down a slot, to
`case 2`; and `nagramx_notification` (`case 1`) and `neko_notification`
(`case 3`) are both new here. The backing config is
`TMessagesProj/src/main/kotlin/xyz/nextalone/nagram/NaConfig.kt:256-260` (key
`"NotificationIcon"`, `configTypeInt`, default `1`), surfaced as 4 labels
(Telegram, NagramX, Nagram, NekoX) at `NekoGeneralSettingsActivity.java:228-232`.

The trap: **the same stored integer already means a different icon in the two
codebases.** A user's persisted `1` is upstream's `nagram_notification` but
this fork's `nagramx_notification` — already a silent divergence, tolerated
because the fork never re-merges upstream's notification-icon UI wholesale.
The exposure is not conditional on this fork adding anything further. As
verified, upstream's domain ends at `case 2`, so the next value upstream
appends would be `case 3` — which this fork already uses for
`neko_notification`. A user who had picked NekoX would then silently get
upstream's new icon after a reconciliation merge, with no error and no
migration to catch it. Appending further fork-only values (`case 4` and
beyond) only widens the overlap. No later upstream commit
extending this domain has been verified, but nothing rules one out — extend
this behavior with a **new fork-owned `NaConfig` key** instead — one whose
value domain upstream has no way to write into — never by widening the value
domain of a key
upstream already owns and may extend.

*(Established 2026-09-05.)*

## `triggerKey()` is firing identity, queue bucket identity, and overview grouping identity

`EventScheduleController.queueKey(...)` composes queue identity as
`account + dialogId + triggerKey` (`EventScheduleController.java:120-125`), so
any change in `triggerKey()` repartitions the runtime send queues. The Message
Triggers screen groups rows by the same key (`MessageTriggersActivity.java:465-479`),
so it is also the overview grouping identity.

`EventScheduleEntry.triggerKey()` therefore has to stay order-independent and
injective for arbitrary user text. The current encoding canonicalizes the
normalized pattern set, sorts with `String` natural order, and length-prefixes
each element before concatenation (`EventScheduleEntry.java:163-174`). A
delimiter-only encoding or order-sensitive list key can collide unrelated
pattern sets, causing both wrong queue sharing and wrong UI grouping.

*(Established 2026-09-03.)*

## One `schedule_date` per forward batch

`TL_messages_forwardMessages` carries a single `schedule_date` field for the
**entire request**, set once on the request object
(`SendMessagesHelper.java:2628-2642`). Forwarding several messages at once
therefore lands every one of them on an identical scheduled timestamp — this
is the stock Telegram API request shape, not a fork defect. It is the root
cause of "I rescheduled one message and several others moved": the scheduled
list re-sorts around the resulting tie in `schedule_date`, which looks like
several messages moved together when only one send actually changed.
Distinct per-message times require distinct forward requests.

*(Established 2026-09-02.)*

## MessageDrawable's static motion background is a foreign invalidate producer

`MessageDrawable` keeps a static `MotionBackgroundDrawable[] motionBackground`
(`ActionBar/MessageDrawable.java:65`), enables `postInvalidateParent` on those
instances (`:231`, `:251`), and consumes them as a shader source
(`getBitmapShader` at `:256`) with bounds updates (`:286`) rather than drawing
that drawable as the chat wallpaper. In the drawable implementation,
`postInvalidateParent` posts the global `invalidateMotionBackground`
notification (`Components/MotionBackgroundDrawable.java:363`) and self-reposts
its own animation runnable every 16ms while active (`:372`).

That makes MessageDrawable a second app-wide producer of
`invalidateMotionBackground` events that are unrelated to the current chat
wallpaper motion. ChatActivity therefore has to ignore producer-mismatch events
for proxy recomposition (`ChatActivity.java:23831`) so those bubble-animation
ticks do not drive unnecessary wallpaper composite refreshes. ThemePreview's
observer branch remains arg-agnostic (`ThemePreviewActivity.java:3599`), so the
payload is safe for existing preview behavior.

*(Established 2026-09-04.)*

## Forwarding aliases the source message's media object

The forward path assigns the new local placeholder's media straight from the
source message rather than copying it —
`newMsg.media = msgObj.messageOwner.media` (`SendMessagesHelper.java:2398`).
`updateMediaPaths`, which reconciles the placeholder once the server confirms
the send, then mutates that same `TLRPC.MessageMedia` object in place — see
the photo-size and live-photo writes at `SendMessagesHelper.java:8759-8760`
and `:8780-8782`. Because of the alias, those writes land on the **original
forwarded message's media too**, not just the copy's.

This is upstream code, not a fork addition. Filed as
[#267](https://github.com/dazewell/Dazegram/issues/267). The copy-send route
avoids it because it builds fresh media from a local path instead of aliasing
(`tw/nekomimi/nekogram/helpers/MessageHelper.createSendingMediaInfo`).

*(Established 2026-09-02.)*

## A shared `extension` variable leaks across a mixed document batch

`SendMessagesHelper.prepareSendingMedia`'s "send as documents" flush declares
one `extension` variable outside its per-item loop
(`SendMessagesHelper.java:10980`), reassigns it per item as each document is
queued (e.g. `:11534-11552`), and then passes its **final leftover value**
as the `mime` argument to every `prepareSendingDocumentInternal` call for the
whole batch (`SendMessagesHelper.java:11718`). A batch of documents with
different extensions therefore gets the wrong MIME type on most of its
members — and since audio-attribute extraction inside
`prepareSendingDocumentInternal` branches on the mime it's handed
(`SendMessagesHelper.java:9377-9396`), a mixed-extension album can also lose
audio attributes it should have kept.

*(Established 2026-09-02.)*

## Sending a captured album as documents can split it by media type

`prepareSendingDocumentInternal` partitions a `groupId` by media type: when
the current item's derived `docType` differs from the previous item's, it
calls `finishGroup` and rotates to a fresh `groupId`
(`SendMessagesHelper.java:9646-9650`, types assigned at `:9631-9645`). That's
correct behaviour for an arbitrary multi-file share where the caller wants
photos and non-previewable files kept in separate groups. It's **wrong** for
a captured source album being resent as documents, where group membership
should follow the original album rather than get re-split by type. Passing
`docType == null` for that call suppresses the rotation, since the guard at
`:9646-9650` requires a non-null `docType` to fire.

*(Established 2026-09-02.)*

## `canEditMessageScheduleTime` has no `id <= 0` guard

`MessageObject.canEditMessageScheduleTime`
(`MessageObject.java:11768-11783`) has no check on `message.id`, unlike its
siblings `canEditMessageAnytime` (`:11745-11766`, bails on `message.id < 0` at
`:11746`) and `canEditMessage` (starting `:11785`, same bail at `:11792`).

This is **not currently exploitable**: an outgoing message with `id <= 0`
that isn't a send-error resolves to `MESSAGE_TYPE_INVALID` in
`ChatActivity.getMessageType` (`ChatActivity.java:20672-20696`), and
`processRowSelect` refuses to select any row whose type is below
`MESSAGE_TYPE_MEDIA` — which `MESSAGE_TYPE_INVALID` (`-1`) is
(`ChatActivity.java:21294`). The scheduled-message Reschedule path never
reaches a message in that state **for a directly-selected single message**;
the bulk path has a separate, unproven gap covered by its own entry below
("Bulk reschedule's album expansion bypasses the selection type check").
Recorded here as a latent gap with its shadowing guard so a future change to
`getMessageType` or `processRowSelect` doesn't silently reopen it.

*(Established 2026-09-02, citations refreshed 2026-09-03.)*

## `ShareAlert.darkTheme` (`= forCall`) is not VoIP-exclusive — it's just a misleading name

`ShareAlert.darkTheme` is assigned `= forCall` in the constructor
(`ShareAlert.java:452`). It has nothing to do with the app's light/dark theme
setting, but it is **not** exclusive to the VoIP call-invite screen either —
an earlier version of this entry claimed that, and it was wrong. Two callers
pass `forCall = true`: `GroupCallActivity.java:6722` (the VoIP group-call
invite share, with `copyLink2` non-null so `linkToCopy[1] != null`, reaching
the `dp(111)` header-height branch — not dead code) and
`PhotoViewer.java:8780` (ordinary photo/video sharing from the media viewer,
with `copyLink2 == null`, so it stays on the `dp(58)` branch despite
`forCall == true`). Every other `ShareAlert` construction site, including the
`ChatActivity` channel-post share arrow (`ChatActivity.java:42773`), passes
`forCall = false`.

The header-height ternary that appears throughout the file
(`dp(darkTheme && linkToCopy[1] != null ? 111 : 58)`, e.g.
`ShareAlert.java:1170`) branches on `darkTheme`, so a dark-looking screenshot
of the share sheet still tells you nothing about which branch is live — that
part holds. It just doesn't mean the caller is a VoIP screen; check the
actual constructor call and its `copyLink2` argument.

*(Established 2026-09-03.)*

## `allowSelectChildAtPosition`'s `y` is grid-local in the non-fullscreen case; adding `systemInsets.top` double-counts it there

`ShareAlert`'s `gridView` and `searchGridView` both override
`allowSelectChildAtPosition(x, y)` to gate taps below the header
(`ShareAlert.java:1168`, `:1253`).

**Non-fullscreen (`isFullscreen == false`) — the only case any current caller
reaches:** `containerView.onMeasure` sets `getPaddingTop()` to
`systemInsets.top`, gated by `if (!isFullscreen)` (`ShareAlert.java:699-703`),
and `onLayout` places every `Gravity.TOP` child, including the grid, at
`getPaddingTop() + topOffset` (`ShareAlert.java:855`). So in this case the
grid's `y` already has the status-bar inset netted out before the guard ever
runs, and adding `+ systemInsets.top` to the threshold double-counts it —
pushing the tap dead band down over the entire first avatar row, a silent
miss with no visual feedback that reads to a user as "the app ignored my tap"
rather than as an error.

**Fullscreen (`isFullscreen == true`):** the `setPadding` call above is
skipped entirely, so `getPaddingTop()` doesn't carry `systemInsets.top` and
the coordinate math differs from the case above. No current caller constructs
`ShareAlert` with `fullScreen = true` — checked every `new ShareAlert(...)`
and `ShareAlert.createShareAlert(...)` call site in the tree — so this branch
is presently unexercised. Worth knowing if a future caller ever does pass
`fullScreen = true`: the fix here was scoped to the reachable
(non-fullscreen) case only.

The identical `+ systemInsets.top` term is *correct* two hundred lines away,
in `containerView`'s own `onDraw` (`ShareAlert.java:928`, `:930`): there it
converts a grid-local `scrollOffsetY` into `containerView`'s own canvas space,
which is not padding-translated. NagramX's fix removes the extra term at both
`allowSelectChildAtPosition` call sites, leaving `y >= dp(...)` with nothing
added.

*(Established 2026-09-03.)*

## Vendored `update to <version>` commits are single-parent squashes, not merges

Commits like `37bd22c0f4` ("update to 12.7.0 (6740)") that bulk-vendor an
upstream Telegram release have a single parent (`628eabc372`) rather than
being a 3-way merge. A fork fix living in a file one of these commits
rewrites is therefore **silently overwritten with no merge conflict to flag
it** — there's nothing to alert the next vendoring pass that a line it's about
to replace was deliberately changed. `ShareAlert.java` alone has been
rewritten by six such bumps since 2025-11.

Concretely, for the exact hook this investigation touched
(`ShareAlert.java:1169-1170`): `37bd22c0f4`'s diff shows it *replacing*
`+ AndroidUtilities.statusBarHeight` with `+ systemInsets.top` in
`allowSelectChildAtPosition` — re-expressing an inset term that was already
there under a different API, not introducing one from a bare `dp(...)`
threshold. A vendoring commit rewriting a line doesn't announce whether it's
carrying a term forward, changing its source, or dropping fork-added
behaviour; only reading the actual diff tells you which. This is why a
one-token fork fix in a hot upstream file needs a `// NagramX:` comment
explaining the *why*: the comment is the only thing that survives to tell a
future investigator the line was intentional, since the diff itself won't.

*(Established 2026-09-03.)*

## Bulk reschedule's album expansion bypasses the selection type check

The single-message reschedule path is gated: `ChatActivity.getMessageType`
returns `MESSAGE_TYPE_INVALID` for a not-yet-reconciled outgoing message
(`id <= 0`, not a send error, `ChatActivity.java:20672-20696`), and
`processRowSelect` refuses to select anything below `MESSAGE_TYPE_MEDIA`
(`ChatActivity.java:21294`) — see the matching dead-end entry. The bulk
`RescheduleSpreadExecutor` path does **not** inherit that gate the same way.

`resolveRescheduleItems` picks an album's representative as the **minimum-id**
member of the group (`ChatActivity.java:37756-37762`,
`if (group.messages.get(k).getId() < first.getId()) first = ...`) — not the
message the user actually selected, and with no positivity check on that
comparison. A still-sending sibling carries a negative local id, which sorts
below every positive server id, so it can become `first` (and therefore
`target.id`) outright.

Separately, `EventScheduleBulkArmer.AlbumIdentity.of`
(`EventScheduleBulkArmer.java:79-91`) captures every `group.messages.get(k).getId()`
into `serverIds` (→ `RescheduleSpreadExecutor.Target.albumIds`) by iterating
the live group map directly, with no `getMessageType`/selectability check per
member — it only ever sees the *representative* that passed selection, not
each sibling. A non-positive sibling id can therefore land in `albumIds` even
when the representative itself is positive and was validly selected.

Reachability of either case through the shipped UI is **unproven either
way** — album sends have not been observed acking asynchronously enough to
leave one sibling negative while another is already positive — this is
recorded as a live gap, not a confirmed defect. `RescheduleSpreadExecutor.sendNext`
guards against both shapes directly (`target.id <= 0` and any non-positive
`target.albumIds` member) rather than relying on this selection-level gating,
since the gating above was never proven to reach this executor's inputs.

*(Established 2026-09-03.)*

## Release builds strip `Log.v` and `Log.d`

`TMessagesProj/proguard-rules.pro:173-176` has an `-assumenosideeffects` block
for `android.util.Log` that lists `v(...)` and `d(...)`, so R8 removes every
`Log.v` and `Log.d` call from the minified release variant. `Log.e`, `Log.i`
and `Log.w` are not listed and survive. Any diagnostic that has to appear on a
real device must use one of those three — a `Log.d` line compiles fine and then
emits nothing once installed.

The **local debug compile gate cannot catch this**:
`:TMessagesProj:compileDebugJavaWithJavac` builds the non-minified debug
variant, where the rule does not apply and `Log.d` works. The stripping only
happens in the minified release build that `staging.yml` produces — which is
the only variant that ever reaches a phone. So a `Log.d` diagnostic passes the
gate, passes CI, installs, and is silent, with nothing upstream of the device
to flag it.

Cost the `#repost-spread` instrumentation a full device test cycle: the
`NAX_SPREAD_DIAG` logging was written with `Log.d`, produced zero logcat output
on the installed staging APK, and had to be reissued at `Log.e`. Referenced by
[PR #270](https://github.com/dazewell/Dazegram/pull/270).

*(Established 2026-09-02.)*

## Notifications post from two independent builders — fixing one leaks the other

`NotificationsController` renders a message notification twice over: the account
**summary/group** is built in `showOrUpdateNotification` (the `mBuilder` InboxStyle
around `NotificationsController.java:4392-4423`, posted as `mainNotification` inside
`showExtraNotifications`), and each **per-dialog child** is built separately in
`showExtraNotifications` (`:4908`+). Both read real `pushMessages` content. Anything
that means to suppress a chat's real name/sender/text has to intercept **both**: a
child-only change still leaks every real line through the summary's InboxStyle. The
disguised-cover engine resolves the exact covered-dialog set in a **preflight** at the
top of `showOrUpdateNotification`, before any real identity/content is read
(`:4140`+), then threads that one immutable set + grouping into `showExtraNotifications`,
which builds a fresh generic summary instead of the real one when any covered dialog is
present (`naxBuildCoverSummary` at `:5878`) and swaps each covered child for a fresh
tagged builder, routing on `naxCoveredSet.contains(dialogId)` (`:5038`+).

## Popup notifications are a third leak surface, separate from summary + children

`popupMessages` is fed from `addToPopupMessages(...)` during new/edit processing,
outside the summary/child builder flow (`NotificationsController.java:947-977`,
`:1196-1200`, `:1253-1256`). A cover implementation that only swaps summary/child
notifications still leaks covered content through popup windows unless this path is
blocked too.

The hardened cover path now blocks covered members at source
(`NotificationCoverController.blocksPopupMessage(...)` in `addToPopupMessages`,
`NotificationsController.java:949-952`) and also purges already-queued popup rows in
the covered preflight pass (`NotificationsController.java:4194-4210`) so enabling
disguise mid-stream cannot leave stale covered popup cards behind.

*(Established 2026-09-03.)*

## `validateChannelId` observes/creates a chat-named OS channel as a side effect

`showExtraNotifications` calls `validateChannelId(lastDialogId, ...)` on the summary
builder (`NotificationsController.java:4939`), which synchronizes against — and can
create — a real per-dialog `NotificationChannel` named after the chat. Reusing it for
a covered chat would leave the chat's real name visible in Android Settings even though
the notification itself is disguised, so that call sits in the **non-covered branch
only**: when any dialog is covered the real summary is never built and this is never
reached. Cover channels are created directly (not through `validateChannelId`) so they
never adopt real-chat identity.

## `minSdk` is 27, so the `SDK_INT <= 19` notification branch is dead

`build.gradle:36` pins `minSdk = 27`. The `Build.VERSION.SDK_INT <= 19` early-return in
`showExtraNotifications` (`NotificationsController.java:4944`) and the other
`<= 19` guards never execute on a shipped build; don't spend effort covering them, and
treat `<= 27` conditions as "always true on the oldest supported device."

*(Established 2026-09-03.)*

## Process-global spoiler atlas publishes one full animation generation per pass

`SpoilerEffectBitmapFactory` is a process-global singleton atlas producer
(`SpoilerEffectBitmapFactory.java:26-47`), and `SpoilerEffect.draw` is the path
that submits active bounds into that producer (`SpoilerEffect.java:323,329`).
Text surfaces use this path through spoiler clip-out + draw in `SimpleTextView`
(`SimpleTextView.java:1210-1218,1247-1248`). Pinned-bar media thumbs use the
same path via the top-panel `BackupImageView` overlay's embedded
`SpoilerEffect` (`ChatActivity.java:12765-12791,30841-30843`). In-message media
particles are a separate renderer (`SpoilerEffect2`) in `ChatMessageCell`
(`ChatMessageCell.java:15399-15405`) and are not controlled here.

Current invariant: every accepted background publish is one full-atlas
generation. The update runnable now allocates background bitmap/canvas once,
then on each accepted pass erases the full bitmap (when reusing) and runs one
unconditional `doDraw(backgroundCanvas, fullRegion)` before publish copy and
UI-thread shader swap (`SpoilerEffectBitmapFactory.java:146-168`).

Why partial repaint cannot be coherent on this atlas: producer-side simulation
advances by cell intersection with the trigger union (`Rect.intersects` in
`SpoilerEffectBitmapFactory.doDraw`, `SpoilerEffectBitmapFactory.java:96-104`),
while particle admission is clip-relative with a damage margin
(`SpoilerEffect.java:342,369-372,464`). At the same time, `applyClip` maps view
bounds into wrapped atlas coordinates and unions wrapped segments
(`SpoilerEffectBitmapFactory.java:120-130`). Clipping rasterization to only a
subset of those intersected cells necessarily mixes generations across adjacent
texels and overlapping mapped bounds.

Cost facts that stay true regardless of clip strategy:
`Utilities.copyBitmaps(backgroundBitmap, nextBufferBitmap)` is already a full
bitmap copy on every accepted pass (`SpoilerEffectBitmapFactory.java:163`);
native implementation copies the full pixel payload (`image.cpp:1249-1334`,
contiguous path `memcpy(rowBytes * height)` at `image.cpp:1327`).
Full draw also already exists on first UI paint and on LiteMode restore
(`SpoilerEffectBitmapFactory.java:79,86`), so mechanism B aligns publish with
the existing full-generation paths instead of introducing a new one.

The dirty-union trigger drop is intentionally not fixed here. `checkUpdate` +
`applyClip` + `clipRegion` remain trigger-only (`SpoilerEffectBitmapFactory.java:112-130`),
and the callback still clears that union each frame (`SpoilerEffectBitmapFactory.java:136-139`).
With full-atlas publish, missed trigger unions may reduce temporal smoothness
but no longer produce spatially mixed generations.

Threading hazard to keep: `isRunning` is cleared only after the UI publish hop
sets `currentBitmapBuffer` and shader (`SpoilerEffectBitmapFactory.java:165-168`).
Clearing it earlier would allow a new pass to start writing while the previous
buffer index is still pending publication.

*(Established 2026-09-04.)*

## The `transtale` -> `translate` package rename means upstream `transtale/*` changes must be ported, and upstream callers of `transtale` symbols silently fail to compile

Fork commit `0887abcd02` ("chore: fix typos & optimize imports") renamed the
translation package `tw.nekomimi.nekogram.transtale` ->
`tw.nekomimi.nekogram.translate` (`git show --stat 0887abcd02` shows the git
rename explicitly as `.../nekogram/{transtale => translate}/Translator.kt`).
Our live translator is
`TMessagesProj/src/main/java/tw/nekomimi/nekogram/translate/Translator.kt`; the
old `transtale/Translator.kt` path no longer exists on `dev`. So any upstream
change to a file under `transtale/` arrives as a modify/delete conflict
(deleted on our side), and any upstream code that *calls* a symbol added under
`transtale/` will not resolve against our tree until the symbol is ported into
`translate/` by hand.

This bit the 2026-09-06 sync of `NextAlone/Nagram` onto anchor
`b03d83df87`: upstream added `Translator.translateShowAlert(...)` to its
`transtale/Translator.kt` and called it from a bot-button long-press menu at
`981806a992:TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java:41468`
(the immutable sync snapshot -- cite that, not the moving `nagram/dev` ref,
which drifts on every upstream push and rots the line number).
Because the merge produced no conflict at that call site (it landed inside a
`ChatActivity` region that did conflict, but the call itself was conflict-free
text), taking upstream's `ChatActivity` hunk without porting the helper would
have pushed a `dev` that does not compile -- the same silent non-compile class
`.github/sync/pins.env` records from the 12.10.1 sync. The fix was to port
`translateShowAlert` into `translate/Translator.kt`, adapted to the fork's
reworked translate API (no `TranslateDb.currentTarget` cache; `AlertUtil`
progress/copy/failure dialogs), and take the `ChatActivity` call.

The standing rule this leaves for every future sync: whenever upstream touches
a `transtale/*` symbol, compare the incoming change against the candidate's
`translate/*` implementation **and its callers**, and port anything missing.
Don't lean on a one-shot presence check like `git grep translateShowAlert
origin/dev` -- that grep succeeds forever once this PR lands and would falsely
reassure even if a *later* upstream change to the same helper went unported.
The failure is silent at merge time (upstream callers of `transtale` symbols
compile against upstream but not against our renamed tree), so it only surfaces
at the compile gate; that is the check to trust, not a grep.

*(Established 2026-09-06, during the NextAlone/Nagram sync reconciliation, snapshot `981806a992`.)*

## The aggregated group summary notification bridges every pushed chat's message text to Wear, even when a chat's own child notification is `setLocalOnly`

`NotificationsController.showExtraNotifications(...)` posts one per-dialog child
notification per chat plus, when `useSummaryNotification` is true — API 27
(`O_MR1`) and below unconditionally, otherwise only when more than one non-story
message dialog is pushed (`sortedDialogs.size() > (storyPushMessages.isEmpty() ? 1 : 2)`,
`NotificationsController.java:4958`) —
a single aggregate summary built from `notificationBuilder` — the `mBuilder`
first assembled back in `showOrUpdateNotification`. That summary's `InboxStyle`
is not a count: it adds up to 10 lines of real `getStringForMessage(...)` output
— sender names and message text drawn from every pushed dialog
(`NotificationsController.java:4443`) — and it is **never** given `setLocalOnly`.
The trap: giving a per-dialog child `setLocalOnly(true)` (encrypted chats have
always done this, `:5834`; the `#wear-messages` per-chat "Show on Watch" toggle
does it at `:5838`) does **not** stop that chat's text riding to a paired Wear OS
watch, because the *summary* is a separate `Notification` without `setLocalOnly`.
So a per-chat "keep this off the watch" control governs the child but not the
shared summary; whenever Android builds that summary it can still preview a
switched-off chat on the watch — and on API 27 and below it builds one even for a
single unread chat, so a lone switched-off chat is not fully hidden there either.

This is upstream behaviour, and it is exactly how Telegram already treats secret
chats: their child is local-only (`:5834`) while the aggregate summary that
previews them is not. **`#wear-messages` deliberately does not fix it** — the
shipped feature is the two per-dialog hooks only (child `setLocalOnly` at the
shared builder chokepoint `:5838`, and the disguised-cover child card in
`NotificationCoverController.postChild:734`). The accepted, product-approved
consequence is the summary leak above. Three cheaper summary fixes were tried
across review and rejected, so do not re-derive them:

- **Gate the whole summary local-only when any pushed chat is watch-off** (an
  early revision's batch-wide `naxAnyWatchOff` scan). Wrong: the fork's disguised
  "cover" children still use `GROUP_ALERT_SUMMARY` on the upstream-silent path
  (`NotificationCoverController.java:724-799`), and older builds routed every
  covered child through that same silent grouped path; blanking the summary
  therefore silenced watch *alerting* for watch-on chats in those grouped
  cases the moment one unrelated chat was switched off — a real on-device
  regression with "Disguise notification" on.
- **Re-arm the children with `GROUP_ALERT_CHILDREN`** so they alert without the
  summary. Dead: children fall back to `OTHER_NOTIFICATIONS_CHANNEL`, which is
  created with sound/vibration/lights disabled (`:292-296`), so flipping alert
  ownership to the children mutes everything anyway.
- **Redact the summary content** to a safe-representative form. Viable but not
  bought: it needs a safe representative across both the `InboxStyle` branch and
  the non-inbox branch, plus title, ticker, person, actions, channel and the Wear
  dismissal id — and Samsung skips the inbox summary path entirely (`allowSummary`
  false, `:4366-4367`), so the redaction would have to cover a second
  construction path too. Deferred by product decision, not overlooked.

Eliminating the leak properly needs a different grouping/summary design. Until
then, the per-chat hooks are honest about what they do — they keep a chat's *own*
notification off the watch — and `FEATURES.md` states the summary limitation
plainly rather than implying full suppression.

## Two independent "any overlap hides the Quote button" gates, one of them a private upstream method

`EditTextBoldCursor.shouldShowQuoteButton()` (`EditTextBoldCursor.java:1213-1236`, private, pure
upstream — git log shows only version-bump commits, zero `// NagramX:` markers before this entry)
decides whether the platform's own text-selection popup (`FloatingToolbar`, wired via
`floatingToolbar.setQuoteShowVisible(this::shouldShowQuoteButton)`, `EditTextBoldCursor.java:1194`)
shows a Quote item at all. Before `#toggle-formatting` it hid the item the moment *any*
`QuoteSpan.QuoteStyleSpan` overlapped the selection, so a quote could be added but never removed
from that surface. `ComposerFormattingActions.isQuoteAvailable(Editable,int,int)`
(`ComposerFormattingActions.java:229-241`, fork-owned) enables/disables the glass composer
toolbar's Quote button on the *identical* condition, independently. Both had to be relaxed in the
same change to make quote toggle-off reachable outside the header overflow menu and the
`Ctrl+Shift+.` hotkey (the two paths that already called `makeSelectedQuote()` directly): now
enabled/shown when there's no overlap (add) OR the selection exactly contains one quote block
(remove), still hidden/disabled only for a partial/non-containing overlap. The
`EditTextBoldCursor.java` edit is the one sanctioned upstream-file touch for this change — everything
else lives in `EditTextCaption.java` and the fork-owned `ComposerFormattingActions.java`.

*(Established 2026-09-06, `#toggle-formatting`.)*

Key-format note for a future edit in this area: `WearBridgeHelper` owns the
`nax_wear_<dialogId>` format, but `ProfileNotificationsActivity` repeats the
`"nax_wear_" + dialogId` literal inline for both its read and its write, so a
rename of the key must touch the settings screen too, not just the helper.

*(Established 2026-09-06, during the #wear-messages build; three review rounds on the summary region ended by dropping all summary suppression to the two per-chat hooks only.)*

## `VideoEditedInfo`'s serialised form round-trips on every send, and `parseString` infers `muted` from `bitrate == -1`

The `ve` string is not just a draft/restore mechanism -- the full
serialise/parse cycle runs on the ordinary send path:
`SendMessagesHelper.java:4824` -> `:4969` -> `:5269` -> `MessageObject.java:4473`
-> `MediaController.java:6846`. Crucially `parseString` derives `muted` from the
legacy bitrate slot (`VideoEditedInfo.java:590-599`) rather than storing it, so
any field added to the format is subject to that inference on every send, not
only on restore. Miss this and a change that sets `muted` in memory while also
writing a real bitrate will have the mute silently discarded at parse time,
because a non-`-1` bitrate re-derives `muted = false`. Conversion runs off the
parsed object (`scheduleVideoConvert(message.obj)`,
`SendMessagesHelper.java:6628` -> `MediaController.java:6592`), so the
in-memory value never reaches the transcoder. This is why the silent-video
feature forces the legacy slot to `-1` whenever muted and carries the real
bitrate in a separate version-12 extension block.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## `MediaController.makeVideoBitrate`'s early return skips its own `maxBitrate` clamp

At `MediaController.java:7018-7021`, `minBitrate` is computed and then
`if (originalBitrate < minBitrate) return remeasuredBitrate;` returns *before*
reaching the `maxBitrate` clamp further down (`:7061-7062`). The helper is not
guaranteed to return a value under its own maximum. A 4096x4096 source at
35 Mbps resized to 3840x3840 gives `minBitrate` = 36.16 Mbps and returns
32,812,500 -- above the 28.4 Mbps `VIDEO_BITRATE_2160` tier. This only escapes
for a near-square source whose long edge exceeds 3840; below that the top tier
takes the same-dimension branch instead. Any code that assumes the returned
bitrate sits inside the range the app normally generates will be wrong on
large near-square sources. Two of three reviewers on PR #300 asserted the
same-dimension branch was the only path that could produce an unclamped
bitrate; that is false, and only a direct read of the early return settled it.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## `needConvert()` returns false on `bitrate == -2`, and no conversion means audio is never stripped

`VideoEditedInfo.needConvert()` (`VideoEditedInfo.java:777-780`) returns false
when `bitrate == -2` (the "Original" quality selection, set in
`PhotoViewer.updateWidthHeightBitrateForCompression` around `:22055-22058`).
When it returns false, `prepareSendingMedia` leaves `path` pointing at the
original file (`SendMessagesHelper.java:11455-11460`) and no conversion is
scheduled (`:6620-6628`). The transcoder is the only thing that strips the
audio track, so a muted clip that reaches the send path carrying `-2` uploads
the untouched source with its sound intact. Treat "muted" and
"`bitrate == -2`" as a combination that must never be serialised together. It
is currently unreachable only by accident -- the mute tap normalises the tier
via `getCurrentVideoEditedInfo()` forcing `selectedCompression = 1`
(`PhotoViewer.java:10229-10231`), any editor exit re-stores the normalised
value, and attach-sheet show resets entries
(`ChatAttachAlertPhotoLayout.java:3916` -> `:1919` ->
`MediaController.java:534-556`). None of those are load-bearing by design, so
any change that makes a pre-mute quality tier survive a mute must first ensure
`-2` cannot reach a muted record.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## Rounded grouped settings cards already exist upstream as `RecyclerListView.setSections()` — don't hand-roll them

The rounded, flat, shadowless grouped-card look that every fork settings page
wears is one call, not a layout you build: `listView.setSections(true)`. The
one-arg overload resolves to `setSections(dp(12), dp(16), true)` and thence to
the DEFAULT section-exclusion predicate (`RecyclerListView.java:3284-3296`),
so card boundaries fall wherever an excluded cell (`TextInfoPrivacyCell`,
`ShadowSectionCell`, ...) sits and are auto-detected live per frame. Both fork
settings base classes already call the same one-arg form
(`BaseNekoSettingsActivity.java:146`, `BaseNekoXSettingsActivity.java:125`), so
a page only misses the treatment if it extends `BaseFragment` directly and
never opted in. Prefer the one-arg overload over the explicit-parameter form:
the latter re-declares the exclusion predicate in fork code, a fork-local copy
of an upstream list that drifts silently on the next sync.

The trap sits next door: `RecyclerListView` also takes `forcedSections`, which
pins card ranges to STATIC adapter positions. It is safe only where drag stays
inside one section — `FiltersSetupActivity` uses it for exactly that
(`FiltersSetupActivity.java:1162-1176`, intra-section reorder). On a page whose
drag crosses section boundaries it fails twice at once, because a forced range
also SUPPRESSES auto-detection for its positions in the draw loop
(`RecyclerListView.java:3529-3534`). If the page mutates its item list live
mid-drag (as a cross-zone reorder does), the forced range holds pre-drag
positions for the whole gesture: it draws a card at the wrong bounds AND hides
the correct auto-detected one, self-correcting only on release — a defect that
never fails CI and never shows in a static screenshot. On any such page,
`setSections(true)` alone is the whole answer; auto-detection is correct, live
and free.

*(Established 2026-09-06, adopting the treatment on `ComposerLayoutActivity`.)*

## `SlideIntChooseView` swallows the seek bar's drag-end signal

`SeekBarView` exposes exactly two delegate callbacks (`SeekBarView.java:96-97`): `onSeekBarDrag(boolean stop, float progress)` and a default `onSeekBarPressed(boolean pressed)`. There is **no** `onSeekBarReleased` — release is `onSeekBarPressed(false)`. And the terminal drag callback is asymmetric across the two ways a gesture can end: every `setSeekBarDrag(...)` in the terminal branch sits inside `if (getAction() == ACTION_UP)` (`:271`), firing `onSeekBarDrag(stop=true, …)` at `:280` for the non-two-sided slider, so **`ACTION_UP` delivers a final drag callback but `ACTION_CANCEL` delivers none at all** — a cancelled gesture only reaches `onSeekBarPressed(false)` (`:286`), the same release signal `ACTION_UP` also sends after its drag callback. Press is `onSeekBarPressed(true)` from the move handler (`:313`). `SlideIntChooseView`'s anonymous delegate (`SlideIntChooseView.java:95-115`) throws all of that away: it ignores `stop`, overrides neither pressed callback, narrows the reported callback to `Utilities.Callback<Integer>` (a bare value, no drag-state), keeps `seekBarView` private with no accessor, and — because `setReportChanges(true)` is on for the composer's Toolbar-size slider (`SlideIntChooseView.java:94`) — fires continuously on every step crossed during a drag with no way for a caller to tell a mid-drag step from the last one. So a consumer that needs "the drag ended" cannot get it from this widget, and a consumer that needs "the gesture was cancelled" gets nothing from the drag callback at all.

The fork-only workaround, used by `ComposerLayoutActivity` for the packing-row settle, is to override `dispatchTouchEvent` on the fragment's own root `FrameLayout` (`ComposerLayoutActivity.java:287`) and treat `ACTION_UP`/`ACTION_CANCEL` there as the gesture-end edge. `SlideIntChooseView` calls `requestDisallowInterceptTouchEvent(true)` on `ACTION_DOWN` (`:86-89`), which suppresses the `RecyclerView`'s *interception* but never *dispatch*, so the root still sees every event including the terminal one. Do **not** reach for a debounce/quiet-interval timer instead — users hold this slider still to read the live preview, so a timer fires mid-gesture with the finger down. And do **not** try to add a `getSeekBarView()` accessor or an `onSeekBarPressed` forward to fix it at source: `SlideIntChooseView` and `SeekBarView` carry zero fork edits and are touched by nearly every upstream bump, so an edit there is a permanent rebase tax.

*(Established 2026-09-06, #composer-spacing.)*

## `SlideIntChooseView.getProgress` and `getValue` are not inverses when `betweenSteps > 1`

`SlideIntChooseView` maps a value to a track position with `getProgress` (`:186-197`) and a track position back to a value with `getValue` (`:199-207`), but the two use different arithmetic. `getProgress` computes `Math.round(...) / options.betweenSteps` (`:192`), and because `Math.round(float)` returns `int` and `Options.betweenSteps` is declared `int` (`:345`), that is an **integer division**: for any value that falls between two anchors, the sub-anchor term truncates to `0`, so the value collapses onto its *lower* anchor's track position. `getValue` divides the analogous term by `(float) options.betweenSteps` (`:204`), a float division, so it *does* return real sub-anchor values. The round trip therefore fails: `getValue` hands out `89`, but `getProgress(89)` returns the track position of `85`, not of `89`.

With `betweenSteps == 1` the division is `/1` and both are exact, so this only bites a caller that configures a step table with anchors more than one unit apart and a `betweenSteps > 1` to subdivide them. Two distinct symptoms follow, both silent:

- **Thumb desyncs from its own label on rebind.** `set()` (`:172-184`) drives the thumb with `setProgress(getProgress(value))` while `updateTexts` prints the exact `value`. A between-anchor value lands the thumb on the lower anchor while the label reads the true number. A user's own drag hides this (the thumb rests at the raw finger position and is never re-derived); the first *rebind* of the row exposes it.
- **`setMinValueAllowed` misdraws the dimmed floor band.** It sets `minProgress` to `getProgress(floor)` (`:230`), so a floor that is not itself an anchor truncates: a floor of `88` over anchors `{85,90,95,100}` yields `getProgress(88)=0` and **no band renders**; a floor of `93` yields `getProgress(93)=0.333` (the position of `90`) so the band renders but **stops short** of the real floor.

The fix is fork-side and does not touch the widget: list every reachable value as its own anchor and set `betweenSteps = 1`, so `getProgress` has nothing to truncate. In the composer layout editor this was `SPACING_STEPS`/`SPACING_BETWEEN_STEPS` in `ComposerLayoutActivity`, moved from `{85,90,95,100}` + `5` to the full `85..100` + `1`. Do not "simplify" such a table back to sparse anchors with a larger `betweenSteps` — it reintroduces both symptoms.

The thumb-vs-label desync above is not limited to the `betweenSteps > 1` case — it is the normal resting state of *any* `SlideIntChooseView` after a drag, because the underlying `SeekBarView` never canonicalizes the released position to a discrete step. On `ACTION_UP`, when the touch lands off the thumb, it *does* reassign `thumbX` to the raw finger pixel, clamped to `minThumbX()`/`getMeasuredWidth() - selectorWidth` (`SeekBarView.java:259-262`), before reporting the released progress to its delegate — `setSeekBarDrag(true, thumbX / (w - selectorWidth))` (`SeekBarView.java:280`) — but nothing rounds that position to a step. The one place `thumbX` gets rounded at all doesn't reach the stored field: `onDraw` shadows it into a local (`int thumbX = this.thumbX;`, `SeekBarView.java:496`) before rounding that local when `delegate.needVisuallyDivideSteps()` is true (`:503-505`), which `SlideIntChooseView` hard-codes to `false` (`SlideIntChooseView.java:124-126`) anyway — so even a caller that flipped that gate would only be rounding a draw-time copy, never correcting the field it draws from next frame. Meanwhile the label is driven independently by `updateTexts(value, true)` from the drag callback (`SlideIntChooseView.java:110`). So a thumb released between two representable values rests off-detent while the label already shows the nearest value, and stays there until something *rebinds* the row (`set()` → `setProgress(getProgress(value), false)`, `:172-184`). With `betweenSteps == 1` that rebind lands the thumb exactly on the label's value, which is what `ComposerLayoutActivity`'s gesture-end `snapSliderRows()` relies on to snap all four composer sliders on `ACTION_UP`/`ACTION_CANCEL`.

*(Established 2026-09-06, #composer-spacing.)*

## `SlideIntChooseView.setMinValueAllowed` corrects the thumb upward only, never back down

`SlideIntChooseView.setMinValueAllowed` (`:222-233`) enforces its floor purely through `seekBarView.setMinProgress(getProgress(value))`, and the enforcement is one-directional. `SeekBarView.setProgress(float, boolean)` clamps the new thumb position against `minThumbX()` (`SeekBarView.java:411-412`), and `minThumbX()` (`:353-355`) is derived from `minProgress` — the value left over from the *previous* call. `setMinProgress` (`:229-234`) only re-applies progress when `getProgress() < minProgress`, i.e. it corrects the thumb **upward** only. So *raising* the floor moves a too-low thumb up, but *lowering* the floor never moves a too-high thumb back down.

The consequence bites any caller that rebinds one of these sliders with both a new value and a lower floor in a single `set()` + `setMinValueAllowed()` pair, when `set()` runs first: `set()` drives `setProgress` while `minProgress` is still the old, higher floor, so the thumb is clamped to the old floor's position even though `updateTexts` moves the printed label to the new value. Label and thumb then disagree until something else rebinds the row. In the composer layout editor this surfaced as: Toolbar size 100%→75%→100% across two gestures left Icon spacing reading 85 with the thumb pinned hard right (`ComposerLayoutActivity`'s `TYPE_SPACING` bind). The fix, all fork-side, is to drop the allowed minimum to the slider's own floor **before** `set()` so the value lands unclamped, then raise it to the real floor **after** — never edit `SeekBarView`/`SlideIntChooseView` for it.

*(Established 2026-09-06, #composer-spacing.)*

## `Tools/scripts/requirements.txt` is installed by every publish build, so anything added there taxes the APK pipeline

`staging.yml` runs `python -m pip install -r Tools/scripts/requirements.txt` immediately before invoking `upload.py`, the script that posts a finished build to Telegram (`.github/workflows/staging.yml:454-456`). That requirements file exists for `upload.py` alone — it pins the pyrogram client and its crypto extension, nothing else. Any dependency added to it is therefore downloaded and installed on **every** publish build, including builds that never touch the tool that needs it.

This is not visible from `requirements.txt` itself, which looks like a general-purpose manifest for `Tools/scripts/`. It is not. When the README wall compositor needed Pillow, the dependency went into a separate `Tools/scripts/requirements-images.txt` specifically to keep it off the build path; that file carries the reasoning inline, but only someone already editing it would see it.

Before adding a Python dependency anywhere under `Tools/scripts/`, decide which file it belongs in: the CI-installed one, or a separate manifest installed by hand. Getting it wrong costs build minutes on every publish, permanently, for no benefit.

*(Established 2026-09-06, #docs, PR #294.)*

## `Path.is_absolute()` is `False` for Windows drive-relative paths, so confinement checks need `path.anchor`

A path like `C:foo.png` has a drive but no root, so Python reports `Path("C:foo.png").is_absolute()` as `False` on Windows. Joining it does **not** behave like a relative path, though: `PureWindowsPath.__truediv__` replaces the drive component, so `out_dir / "C:foo.png"` silently discards `out_dir` entirely.

Any guard written as "reject absolute paths, then join" therefore lets drive-relative values straight through and writes outside the directory it was meant to confine. The correct test is `path.anchor`, which is `"C:"` for exactly these values and empty for a genuine relative path.

Both path guards in the wall compositor check `.anchor` rather than relying on `is_absolute()` alone — `_confine_source` for panel sources (`Tools/scripts/compose_walls.py:157-171`) and `_require_plain_png_filename` for wall outputs (`Tools/scripts/compose_walls.py:187-200`). The output guard shipped with only the `is_absolute()` check first and was caught in review; the source guard had the same gap and was closed in the same pass.

*(Established 2026-09-06, #docs, PR #294.)*
## isGhostModeActive() returns true vacuously when all five ghost toggles are locked

Established 2026-09-09 (#ghost-hold). `isGhostModeActive()` `continue`s past any
toggle whose `Locked` companion is set, so if all five are locked the loop body
never runs and it returns its initial `true` (`NekoConfig.java:305-319`).
`setGhostMode` also skips locked items (`NekoConfig.java:321-330`), so in that
state Ghost is permanently on and `toggleGhostMode()` is a no-op. It is not
reachable through the UI today: `GhostModeActivity.onItemLongClick` refuses a
fifth lock (`getGhostModeLockedCount() >= 4`), so at least one toggle is always
unlocked and Ghost stays turn-off-able. Any feature that makes "Ghost never
turns off" harmful (Ghost Hold, whose queue would become unflushable) must rely
on that 4-lock cap, or handle the vacuous-true case itself.

## getUnsentMessages queries scheduled_messages_v2 too, and checkUnsentMessages has two callers

Established 2026-09-09 (#ghost-hold). `MessagesStorage.getUnsentMessages` runs a
second cursor over `scheduled_messages_v2` selecting `mid < 0 AND send_state = 1`
(`MessagesStorage.java:8726`), so local unsent scheduled rows are pulled into the
resend path alongside `messages_v2` rows, and `processUnsentMessages` feeds them
to its scheduled retry loop (`SendMessagesHelper.java:9126`+). This runs not just
at startup: `checkUnsentMessages()` is called from `ApplicationLoader.java:308`
**and** from inside `processSentMessage` (`SendMessagesHelper.java:1811`), i.e.
every time the unsent queue drains during normal use. The current fork-store
design does NOT park held rows here — held rows live in the fork-owned
`ghosthold_<account>.db` and are injected as display-only objects, so a
steady-state drain finds nothing of ours (see `ui-to-code.md`'s "A local
negative-id row in scheduled_messages_v2 renders in the Scheduled list" entry;
the pre-rebuild design that persisted held rows as negative-id
`scheduled_messages_v2` rows was discarded as unsafe). The `scheduled_messages_v2`
drain guards that remain — `processUnsentMessages` skips held rows
(`SendMessagesHelper.java:9147`) and `retrySendMessage` refuses them
(`SendMessagesHelper.java:1766`) — survive for exactly one window: a legacy-upgrade
launch where `checkUnsentMessages()` runs the auto-resend loop before
`checkOnProcessStart()` has migrated the old marked rows out of the stock tables.
In that window a still-marked legacy row could otherwise auto-send while Ghost is
on (a P1 leak); migration makes the guards unreachable after the first
post-upgrade launch. So the stock-table guards are legacy-migration protection,
not defence of a live store — do not read this entry as licence to park held rows
in `scheduled_messages_v2`, and do not delete the fork store on the belief that
they live there.

*(Corrected 2026-09-11, #ghost-hold, PR #347: the original wording said the live
design parks held rows in `scheduled_messages_v2` — it does not; that was the
discarded pre-rebuild design.)*

## AutoDeleteMediaTask's file pin is in-memory only and dies on restart

Established 2026-09-09 (#ghost-hold). `AutoDeleteMediaTask` keeps its "don't
delete this file yet" set in an in-memory structure with `lockFile`/`unlockFile`
(`AutoDeleteMediaTask.java:17`, `:243-267`); nothing persists it, so a process
restart drops every pin, and the sweep itself is time-based and runs at most
once per 24h (`AutoDeleteMediaTask.java:21`, `:118-125`). This is why holding a
*media* message across an arbitrary Ghost duration can't lean on the existing
pin — a kill during the hold would leave the file eligible for the next sweep.
It's the recorded reason Ghost Hold v1 is text-only.

## Sync guard check validates protected pins against the branch tree, not the PR merge ref

Established 2026-09-09 (#ghost-hold). The `protected pins vs HEAD` step of the
sync guard reads each pinned blob from the branch's own tree
(`.github/sync/sync-guard.ps1`, `protected-paths.tsv`), so a branch cut before a
repin lands on `dev` stays red on that check until `dev` is merged forward into
it — merging the branch's own PR does **not** retroactively clear it. Confirmed
from PR #325 (repinned `README.md` `d48354cf…` → `d9373e96…` on `dev`) and PR
#324 (cut beforehand, still failing on head `330451f9de` after #325 merged). The
natural assumption — "the merge ref has both the new pin and the new file, so
it'll sort itself out" — is wrong, and re-deriving it costs a full CI round each
time. This is a recurring shape here, not a one-off: `git log` already carries
`repin signing gradle blob…` commits and a `document the build.gradle
signing-blob pin trap` entry for the same class of problem. The fix is a
`#tag`-exempt merge of `origin/dev` into the feature branch once its tree is
clean.

## canEditMessageScheduleTime lacks the negative-id guard its sibling canEditMessage has

Established 2026-09-10 (#ghost-hold). `MessageObject.canEditMessage(...)` bails
out for a local, unsent row: it returns `false` when `message.id < 0`
(`MessageObject.java:11792`), so the general "Edit" action correctly hides
itself for any negative-id scheduled row. Its sibling
`canEditMessageScheduleTime(...)` (`MessageObject.java:11768-11782`) has **no**
such check — it returns `true` for a DM / megagroup / creator row regardless of
id sign. So the scheduled-list "Edit schedule time" action offers itself for a
purely-local negative-id row and, on tap, issues `TL_messages_editMessage`
against an id the server has never seen. For an ordinary failed-schedule row
that is a harmless failed request; for a Ghost Hold row it is a server round
trip while Ghost is on — the exact exposure the feature exists to prevent.

This is a pre-existing upstream asymmetry, not something Ghost Hold introduced —
any negative-id scheduled row hits it. Ghost Hold guards **only its own held
rows** at the call site (`ChatActivity.java`, the `OPTION_EDIT_SCHEDULE_TIME`
block, mirroring the existing `!isHeld` Send Now guard) and deliberately does
**not** touch `canEditMessageScheduleTime` itself: fixing the upstream method
would widen the diff into shared base-fork code for a bug that costs a failed
request, not data loss. Recorded so the next person who wonders why the held-row
guard exists — or who trusts `canEditMessageScheduleTime` to mirror
`canEditMessage`'s id check — does not burn an investigation on it.

## GhostModeActivity posts mainUserInfoChanged on the selected account, not the fragment's

Established 2026-09-10 (#ghost-hold). `GhostModeActivity` posts
`NotificationCenter.mainUserInfoChanged` on
`NotificationCenter.getInstance(UserConfig.selectedAccount)` — at
`GhostModeActivity.java:153` (the Ghost Hold toggle notice path) and,
pre-existing since before `#ghost-hold`, at `:207` and `:211` (the
`showGhostInDrawer` / `showGhostModeStatus` toggles). The fragment itself
observes via `getNotificationCenter()`, i.e. its own `currentAccount`
(`:90`). So if this screen is ever reached on a non-selected account, the post
lands on a different center than the observer and the rows/held-count refresh is
missed. In practice the screen is opened on the selected account, so the two
coincide and the observer fires.

This is a **pre-existing, fork-wide pattern**, not something Ghost Hold
introduced — the two sibling posts predate it and use the same
`selectedAccount` center. A code review flagged the `:153` post in isolation;
"fixing" only that one line would leave the file internally inconsistent with
its two neighbours three lines down and would be a drive-by edit on pre-existing
code. If the pattern is wrong it is wrong in three places and is a separate
change with its own justification. Recorded so the next reviewer who spots the
`:153` post does not re-raise it as a Ghost Hold defect.
## randoms_v2 never receives a positive mid for a send-now message

Established 2026-09-10 (#ghost-hold). The `random_id -> mid` correlation that
`SendMessagesHelper`'s `alreadySent` path relies on is only usable for messages
that stay scheduled server-side; for a send-now message it can never fire. Three
facts together:

- The mid rewrite in `updateMessageStateAndIdInternal` that writes the confirmed
  server id back into `randoms_v2` is gated `_oldId < 0 && scheduled == 1`
  (`MessagesStorage.java:13923`). A send-now message is `scheduled == 0`, so it
  never enters this branch.
- The `scheduled == 0` id-remap branch (`MessagesStorage.java:14061`+) rewrites
  `messages_v2`, `messages_topics`, `media_v4`, `media_topics` and
  `dialogs.last_mid`, but **not** `randoms_v2`.
- Post-confirmation `putMessages` cannot backfill it either: an incoming server
  message carries `random_id == 0` (`MessagesStorage.java:12248`), and the
  `randoms_v2` insert is gated on a non-zero random id (`:12825`), so the insert
  is skipped.

Cost if missed: any design that keys a held/pending message off `randoms_v2`
expecting to later read back its positive mid on the primary send-now path is
building on a row that is never written. This is the verified fact that ruled
out the original Ghost Hold storage design (a stock `scheduled_messages_v2` row
with a negative id and `send_state = 1`) and drove the rebuild onto fork-owned
state — making it work would have required editing stock
`updateMessageStateAndIdInternal`, changing message-receipt behaviour for every
chat in the app.

## `SendMessageParams.sendAnimationData` is non-null on every ordinary composer send

`ChatActivityEnterView` builds a fresh `MessageObject.SendAnimationData` for a
normal (non-forwarding) text send before constructing the params
(`ChatActivityEnterView.java:9624-9626`), and `of(...)` stores it verbatim
(`SendMessagesHelper.java:12537`). It is a transient UI fly-in hint — the
composer's on-screen x/y/width/height — carrying no part of the sent message,
but it is set on ~100% of composer sends. Any allowlist/denylist that treats an
unrecognised non-default `SendMessageParams` field as "not an ordinary text
send" must exclude `sendAnimationData` (and `updateStickersOrder`, a local
recent-emoji reorder flag, `ChatActivityEnterView.java:9646`) or it rejects
every real message.

Cost if missed: Ghost Hold's fail-closed backstop `onlyPersistedFieldsSet`
refused to hold anything with a non-default unknown field, so `sendAnimationData`
made it refuse **every** composer send — the message went straight to the network
with Ghost on. Cost a full device cycle to surface because the leak is silent and
the send otherwise looks normal (`GhostHoldController.java:389-396`, 2026-09-10).

## `-keep class org.telegram.messenger.* { *; }` DOES keep nested-class members

The single-`*` keep rule (`proguard-rules.pro:9`) is often assumed not to match
a nested class such as `org.telegram.messenger.SendMessagesHelper$SendMessageParams`
(`$` mistaken for a package boundary). It does: in the shipped minified APK
`377d89c` (`org.telegram.messenger.beta`, staging = release R8 config), all 54
instance fields of that class keep their **original** names (`message`, `caption`,
`sendAnimationData`, …) in the DEX — verified with `dexdump` on the exact
installed artifact. So reflection over `Field.getName()` on this class happens to
survive R8 today. It is still not safe to rely on: a keep rule narrowed to
`**`-vs-`*` or dropping `{ *; }` would silently rename these fields and break
name-based reflection with no compile error, which is why Ghost Hold moved its
field check onto compile-checked `p.<field>` references plus a name-independent
declared-field **count** guard (`GhostHoldController.java:353-420`, 2026-09-10).

Cost if missed: this fact killed hypothesis H1 (that R8 renamed the fields and
disabled Ghost Hold's backstop in the minified build only). It did not; the real
cause was the `sendAnimationData` trap above. Re-deriving this costs a minified
build + DEX inspection.

## `SendMessageParams.of(MessageObject)` restores the reply header but NOT entities

On the retry/re-drive path (`retryMessageObject != null`) the outgoing text
request pulls its two formatting-bearing fields from two different places, and
only one of them is the stored message:

- **Reply header rides on the stored message.** `sendMessage` sets
  `newMsg = retryMessageObject.messageOwner` (`SendMessagesHelper.java:4547`) and
  builds `reqSend.reply_to` from `newMsg.reply_to`
  (`SendMessagesHelper.java:5443-5444` via
  `createReplyInput(TL_messageReplyHeader)` at `:227-240`, which also reads
  `reply_to_peer_id` when `flags & 1` is set). So whatever reply header is on the
  stored row is what gets sent -- `of(MessageObject)` passing `replyToMsg = null`
  (`SendMessagesHelper.java:12453`) does not lose it.
- **Entities ride on the params, which `of()` nulls.** The local `entities` used
  to build `reqSend.entities` comes from `sendMessageParams.entities`
  (`SendMessagesHelper.java:4399`, then `reqSend.entities = entities` at `:5462`),
  and `of(MessageObject)` passes `entities = null`
  (`SendMessagesHelper.java:12453`). `newMsg.entities` is never read back into the
  request on this path (the Pangu block at `:5243-5256` only ever re-derives from
  the already-null local, and its `newMsg.entities = entities` write is guarded on
  the local being non-empty, so it does not restore anything either). So a
  re-drive built purely from `of(mo)` ships with **no entities** -- bold, links,
  mentions and custom-emoji all silently gone -- even though the reply header
  survives.

Cost if missed: any redrive/retry that reconstructs a send via
`SendMessageParams.of(MessageObject)` and assumes "it copies everything off the
stored message" is half right. Ghost Hold's flush hit exactly this: the held
message stored its entities on the blob but flushed as plain text until the
re-drive explicitly restored `p.entities = m.entities`
(`GhostHoldController.java:1065-1073`, the `dispatchFreshItem` `of(mo)` block,
2026-09-10, #ghost-hold). The reply header needed no such restore, which is what makes the
asymmetry a trap -- testing a reply-with-formatting would show the reply intact
and the formatting gone, pointing at the wrong half.

## `commit-tag.yml`'s job name is declared unquoted, so its real status-check context is the truncated string `Every commit carries a`, and the required-checks ruleset pins that exact truncation

`commit-tag.yml` declares its job as `name: Every commit carries a #tag`, unquoted (`.github/workflows/commit-tag.yml:16`). In YAML a space-preceded `#` starts a comment, so everything from `#tag` on is discarded and the job's real name — and therefore the **GitHub status-check context** it reports under — is the truncated literal `Every commit carries a` (22 chars, trailing space trimmed). Verified live with `gh pr view 334 --json statusCheckRollup --jq '.statusCheckRollup[].name'`, which returns exactly `Every commit carries a` (2026-09-10).

The no-bypass ruleset that gates merges into `dev` requires **that exact truncated string** as its one status check: ruleset `22861936` (`dev required checks (no bypass)`), `rule=required_status_checks`, context `Every commit carries a`, `strict=false`, `bypass_actors: []` — all confirmed via `gh api repos/dazewell/Dazegram/rulesets/22861936` (2026-09-10). **Quoting the job name to "fix" the YAML would rename the check context to `Every commit carries a #tag`; the required context `Every commit carries a` would then never report, and every PR into `dev` would sit forever waiting on a check that no longer runs — a hard deadlock against the live ruleset.** If the name is ever corrected, the ruleset's required context must be updated **first**, in a separate step, or in lockstep. This is why `commit-tag.yml` is deliberately out of scope for any "tidy the workflow YAML" change.

The reason this ruleset is *separate* from the pre-existing `dev` ruleset rather than a rule added to it: ruleset `18550420` (`dev no-force no-delete + Copilot review`) carries `bypass_actors` including `RepositoryRole 5` (admin) at `bypass_mode: always` (plus a DeployKey and an Integration, same mode) — confirmed via `gh api repos/dazewell/Dazegram/rulesets/18550420` (2026-09-10). An agent runs under dazewell's admin token, so any required check added to `18550420` would be bypassed for exactly the actor it is meant to bind. Ruleset `22861936` has an **empty** `bypass_actors` list, which is why it is a *separate* ruleset: the required `Every commit carries a` context therefore applies to every actor, the admin token included, so no merge an agent can issue lands a commit that fails the tag check. That — tag integrity — is the **only** property this ruleset enforces. It does **not** enforce root-session identity, the named in-chat approval, gate freshness at merge time, or the `.github/sync/**` exclusion; each of those rests entirely on the agent-process prose, with no platform control behind it. So do not read `22861936` as the safety net for conditional agent merge authority as a whole — it is the safety net for exactly one property, and the rest of the authority is only as strong as the prose that describes it. (What `gh pr merge --admin` would do against an empty-`bypass_actors` ruleset has not been tested here, so this file makes no claim either way; the `--admin`/`--auto` prohibition is a rule the agent process imposes on itself, not an assertion about platform behaviour.)

One live behaviour to expect around all this: GitHub computes `mergeable` and `mergeStateStatus` **asynchronously**, and they are distinct fields — `mergeable` settles to `MERGEABLE`/`CONFLICTING`, while `CLEAN` is a value of `mergeStateStatus`. After ruleset `22861936` was created, open PRs read `mergeable: UNKNOWN` (and `mergeStateStatus: UNKNOWN`) for minutes before `mergeable` settled to `MERGEABLE` and `mergeStateStatus` to `CLEAN`, and a PR that had already MERGED read `UNKNOWN` indefinitely (2026-09-10). So `mergeable: MERGEABLE` is never a freshness guarantee — it answers "does this textually merge", not "is this green" — and the instant any merge moves `dev`, every other open PR's `mergeStateStatus` drops back to `UNKNOWN` until a background job recomputes it. Gate on `mergeStateStatus == CLEAN` plus a head-pinned green head check, re-read each time by polling `mergeStateStatus` itself, never on a cached or just-observed `mergeable`.

*(Established 2026-09-10, #docs.)*

## Event-schedule per-account state survives logout unless explicitly torn down, and a BottomSheet picker can straddle the logout

Account slot indices are reused: logging out of an account and logging into a
new one reuses the same numeric slot. Every `com.radolyn.ayugram.eventschedule`
store keys off that slot, not a stable identity, so nothing is cleared for free
on logout. `MessagesController.performLogout` is the single teardown chokepoint
(`MessagesController.java:16388-16402`, beside the `PasscodeHelper`/preset
clears). Two distinct leak shapes must be closed there, needing different
mechanisms:

- **Field-level teardown** for state a stale read would match: each store's
  `clearAccountState(account)` (cache, loaded flag, prefs, and
  `EventScheduleStore.nonEmptyAccounts` bit), plus
  `EventScheduleController.onAccountLoggedOut(account)` for its runtime maps
  (`PENDING`, `QUEUES`/`SUPPRESSED`, `DURABLE_INFLIGHT`, `warmedAccounts`,
  `pendingAccounts`). Miss one and the new account matches the departed
  account's armed triggers on the hot new-message path.

- **Generation-guarded continuations** for writes already *in flight* when
  logout ran. Each store carries a `GENERATION` counter bumped inside
  `clearAccountState`; an action captures the token at intent and the store
  rejects a write whose token no longer matches. Placement matters: the guard
  lives at the store *writer* (`EventScheduleStore.resolveAndClaimForEdit`), not
  the dispatcher, because the bulk-arm path (`EventScheduleBulkArmer` ->
  `RescheduleSpreadExecutor.run` -> `onFinalize` -> `finalizeOnUi` ->
  `reconcileDialogThen` -> `armSurvivor` -> `bulkArmSurvivor`) reaches
  `resolveAndClaimForEdit`/`persist` **without** ever passing through
  `postDurableLookup` -- a token captured only in `postDurableLookup` would
  never fire on that path.

The subtle half: the schedule **picker** is a directly-shown `BottomSheet`
(`AlertsCreator.createScheduleDatePickerDialog`, `AlertsCreator.java:4406`),
never registered as the fragment's `visibleDialog`, so
`LaunchActivity.switchToAvailableAccountOrLogout`'s fragment swap does **not**
dismiss it on logout -- the same structural gap #330 documented for the child
sheet via `presetLogoutObserver` (`EventScheduleHelper.java:945-952`). So a
generation captured *when the picker opens*, or at the top of `commit()`, can
read the already-bumped post-logout value, making every downstream check a
tautology. The tokens are therefore captured at `EventScheduleHelper.Row`
**construction** -- before the picker is shown (`EventScheduleHelper.java:277-279`,
`:417-422`) -- and re-verified fail-closed in `snapshot()` and `commit()`
(`EventScheduleHelper.java:1632`, `:1646`). `snapshot()` returning null also
stops any `EventScheduleBulkArmer` being built for a departed slot, and
`armPending -> EventScheduleStore.persist` carries no token of its own, so that
`commit()` gate is the only thing covering it.

The picker is not the last non-dismissed surface on the bulk path. A selection
of more than 50 messages defers the whole reschedule behind a **second** bare
`AlertDialog` confirmation (`ChatActivity.java:37750-37756`), built directly and
likewise never a `visibleDialog`. `EventScheduleBulkArmer.onAdmission` runs
*inside* that dialog's positive-button callback, so a token re-read there sees
the post-logout value just as the picker case does -- one window later. The
construction-time `storeGeneration` therefore travels the whole way: `snapshot()`
packs it into an `EventScheduleHelper.TriggerArmIntent` carrier
(`EventScheduleHelper.java:1635`) threaded through the reschedule delegate
(`AlertsCreator.java:4312`) into the armer, which compares the carried token at
admission (`EventScheduleBulkArmer.java:213`) and fails closed before it
registers an observer or suppresses the new occupant's triggers. Lesson: a
generation captured before *any* directly-shown dialog must be carried to the
actual mutation site, never re-derived past the dialog.

A reused slot also gets installed a *second* way -- `LoginActivity.onAuthSuccess`
(`LoginActivity.java:1710`), which already carries its own fork per-slot reset
(`PasscodeHelper.clearAccountState`, `:1715`) -- so it is fair to ask whether the
event-schedule clears belong there too, or behind some shared hook both paths
share. They do not: `performLogout` stays the sole chokepoint that matters for
*our* state, because a slot can never reach a login picker without it having run
first. Every slot-picking login entry point gates on
`!UserConfig.isClientActivated()`, i.e. `currentUser == null`; `currentUser` is
nulled in exactly one place, `UserConfig.clearConfig()`; and every
`clearConfig()` call site outside `onAuthSuccess` itself is either inside
`performLogout` or immediately followed by `performLogout(0)` in the same
UI-thread runnable (the native auth-key-unregistered path and the SESSION_REVOKE
push path both do `clearConfig()` then `performLogout(0)`). So the
remote-session-revoked-while-closed case still tears our state down before any
picker sees the slot as free. Extending `MessagesController.cleanup()` into a
shared clear-hook was considered and **rejected**: it is upstream's generic
runtime reset, called from two sites including `onAuthSuccess` itself
(`LoginActivity.java:1716`), so a persisted store-clear parked behind it becomes
a data-loss bug the day upstream adds a non-logout caller -- its call-site set is
upstream's to grow, not ours to police.

*(Established 2026-09-10, `#eventschedule`, PR #338 -- closing the logout leak
across `EventScheduleLastSetup`, `EventScheduleStore`, and the controller, on
top of the `EventSchedulePresetStore` fix in #330.)*

## `TL_ephemeral.TL_sendMessage` is not a text RPC -- it is the merged wrapper for both `messages.sendMessage` and `messages.sendMedia`

The name reads like a text-only send, and it is not. When a send has an
ephemeral receiver bot set, `EphemeralMessagesHelper#beforeSendingFinalRequest`
rewrites the outgoing request into a single `TL_ephemeral.TL_sendMessage`
regardless of what it started as: the `TL_messages_sendMessage` branch
(`EphemeralMessagesHelper.java:141`) sets `newRequest.media = null` (`:155`),
and the `TL_messages_sendMedia` branch (`:182`) copies `newRequest.media =
request.media` straight across (`:196`). The class declares `public
TLRPC.InputMedia media` (`TL_ephemeral.java:343`) precisely because of that
second branch, and
`FileRefController.java:161-163` shows it routinely carrying paid media and
polls. The ephemeral receiver itself is resolved for every send including
media, from the caption where there is one
(`SendMessagesHelper.java:4439-4450`).

Cost of missing it: any code classifying outgoing requests by TL class that
files this class under "text" will silently mis-handle ephemeral photo, video,
document, poll and paid-media sends. Silent in the literal sense: no crash, no
log, nothing to notice. If the text/media distinction actually matters, the
discriminator is `media == null`, which is what the two branches above
establish; but prefer not to classify user intent by request type at all,
since the reverse case bites equally -- a typed message with a resolved link
preview leaves as `TL_messages_sendMedia` carrying `TL_inputMediaWebPage`
(`SendMessagesHelper.java:5363-5380`), not as `TL_messages_sendMessage`.

This is a warning for a future classifier, not a description of existing code.
The fork's nearest thing to one, the Ghost Mode send warning
(`GhostSendWarningHelper.java`), deliberately does **not** classify: it
allowlists request classes as "this produces a message" and then resolves the
destination chat, and it draws no text/media distinction anywhere. That design
was chosen in review precisely because of this trap.

*(Established 2026-09-10, #ghost-type-warning -- found in design review, before
the mis-classification reached code.)*

## Ghost Hold: held rows ride the Scheduled-list bulk actions without a per-action guard

**Status (2026-09-11): resolved by an entrance guard — the present-tense
description in the next two paragraphs is the pre-fix behaviour, kept for the
reasoning. See "First fixed with a shared send boundary … then superseded by an
entrance guard" below for what actually ships now.**

A held Ghost Hold row is a real `TYPE_TEXT` `MessageObject` with a negative
local id, and it renders in the Scheduled list like any other row, so it is
selectable into multi-select -- `addToSelectedMessages`
(`ChatActivity.java:20877`) adds it to `selectedMessagesIds` and, because the
type is text, to `selectedMessagesCanCopyIds` too. That means a bulk action the
Scheduled action mode exposes acts on it unless either its own held guard or its
scheduled-mode visibility gate excludes it. Send-Now
(`confirmSendNowSelectedMessages`), the single-row context menu, and
edit-schedule-time each guard it individually; the reschedule spread did not
until it was excluded at the `resolveRescheduleItems` chokepoint
(`ChatActivity.java:37877`).

Getting the *reachable* set right matters, because two of the send-capable
overflow items are already hidden in scheduled mode and three others are not.
`nkbtn_savemessage` and plain `nkbtn_repeat` are added to the overflow at
`ChatActivity.java:11505-11506` but their visibility is set to `canForward`
(`ChatActivity.java:21051`, `:21054`), and `canForward` is
`chatMode != MODE_SCHEDULED && ...` (`ChatActivity.java:21036`) -- so both are
**hidden** on the Scheduled list and cannot act on a held row there. The three
that *are* reachable and unguarded, and would push held text to the server now,
are: `combine_message` (visibility gated only on copyable selection,
`ChatActivity.java:11531`; handler `:4333`), `nkbtn_repeatascopy` (visibility
`canSendMessage && (!noforwards || canSendMessagesAsCopy(...))` with no
scheduled gate, `ChatActivity.java:21057`; handler `:48257` ->
`doRepeatMessage` `:49107` -> `sendMessagesAsCopy`), and the scheduled
`forward` overflow item (added at `ChatActivity.java:11521` under
`getActionBarButtonForward()`, enabled via
`canSendMessagesAsCopy(getSelectedMessages1())` at
`ChatActivity.java:21151`). The lesson: guarding held rows action-by-action
is the wrong shape, because the held row is admitted to the *selection* upstream
of every action; the durable fix is to keep held rows out of the send-capable
selection at one boundary, not to chase each new action.

**First fixed with a shared send boundary (`naxExcludeHeldFromSend`,
`ChatActivity.java:37203`), then superseded by an entrance guard.** The boundary
filtered `isHeld` rows out of each send assembler, but that is exit-filtering and
it kept losing: it missed the reply/quote payload channel (the message-preview
repopulation that puts straight into `selectedMessagesIds`) and off-screen range
selection, which calls `addToSelectedMessages` directly and bypasses the
`getMessageType`/`processRowSelect` gate. The durable fix is to seal the
*entrance*: a held row never enters the selection model at all. But "the
selection model" is not one field -- it is every container that holds selected
message ids, and there is more than one door into that room. The main
multi-select model (`selectedMessagesIds`, plus the canCopy/canStar subsets that
ride with it) has two insertion points -- `addToSelectedMessages`
(`ChatActivity.java`) and the message-preview reply/quote repopulation -- and both
now return/skip on `isHeld`; `canSelect` refuses held rows too so the drag/range
route is clean before it ever reaches `addToSelectedMessages`. Separately, the
message **preview** keeps its own selected-id set
(`MessagePreviewParams.forwardMessages.selectedIds`) that `beforeMessageSend`
reads directly through `getSelectedMessages` -- independent of
`selectedMessagesIds` -- so that container is sealed at the single `showFieldPanel`
forward funnel every `showFieldPanelForForward` caller passes through, filtering
`messageObjectsToForward` through `naxExcludeHeldFromSend` before `updateForward`
builds it. The reply/link preview sets (`replyMessage.selectedIds`,
`linkMessage.selectedIds`) are single-target/webpage-derived and structurally
cannot hold a held row, so they need no seal -- listed only so the container set
is complete. With every entrance sealed, no selection-consuming route (forward,
quote, reply, copy, combine, repeat-as-copy, draft publication, range select) can
carry a held row, by construction rather than enumeration. The pre-existing
per-action `!isHeld` guards (Send Now, reschedule, edit-schedule-time) and
`naxExcludeHeldFromSend` at the assemblers are left as harmless defense-in-depth;
they are now redundant with the entrance guard.

Because a held row is no longer selectable, its only action -- **delete** -- comes
from the single-row context menu's cancel path (`getMessageType` returns
`MESSAGE_TYPE_INVALID` for the `id <= 0` out row, which populates the cancel item
when `isSending()`), routed through `cancelSendingMessage` -> `deleteMessages` ->
the fork `messagesDeleted` observer that removes the durable record. That delete
path depends on `isSending()`, which depends on `send_state = SENDING` -- see the
reload trap below.

**Reload trap: `send_state` is client-only and not in the serialized blob.** A
freshly held row carries `send_state = SENDING` (`GhostHoldController` sentinel
build), but the render injection decodes the stored blob and `send_state` is not
part of it, so a row shown on a later launch came back as `NONE`. `isSending()`
was then false, `getMessageType` classified it invalid, and the single-row
cancel/delete affordance was never populated -- so a held message could not be
deleted after an app restart until flush. Stock restores `send_state` from its own
column on the scheduled read (`MessagesStorage.java:9012-9014`); the render
injection now does the equivalent on the decoded display object before building
the `MessageObject`. Durable membership stays governed by `STATE_HELD`, never by
`send_state`.

*(Established 2026-09-10, `#ghost-hold`, during the ghost-hold-audit branch
superseding PR #336. The shared send boundary landed 2026-09-10 on the
ghost-hold-selection branch; superseded 2026-09-11 by the selection-model entrance
guard and the reload `send_state` restore on the ghost-hold-selectability branch,
`#ghost-hold`. Round-2 completion 2026-09-11, PR #347: the entrance guard was
extended to the preview's separate `forwardMessages.selectedIds` container after a
review found `beforeMessageSend` reads it directly, and the scheduled-count
publication was corrected to thread the initiating session token through its
callbacks rather than recapture `sessionEpoch` at publish time -- a callback that
began under an older session could otherwise sample the new epoch and pass the
recheck, posting a stale count into whoever now owns the reused account slot.)*

## Ghost Hold: legacy migration can resurrect a message deleted mid-migration (accepted, #346)

The one-time legacy migration (`GhostHoldController.migrateAccount`) collects
pre-fork-store held rows on the **storage** queue, then inserts them into the fork
store on the **fork** queue. The deletion path is the other half of the trap:
`MessagesController.deleteMessages` posts `messagesDeleted`
(`MessagesController.java:9545-9569`) immediately after enqueueing the stock
delete, and the fork `messagesDeleted` observer removes the matching fork record
on the fork queue. If a user deletes a legacy held row *during* migration, the
observer can run before the migration insert -- it finds no fork row yet, so its
removal no-ops and the delete signal is lost; the migration insert then re-adds
the collected blob and the row comes back. "I deleted it and it came back" is a
durable user-intent violation, but closing it race-free is storage-lifecycle work
the safety bundle scopes out: a bare existence check at insert time cannot
distinguish "not yet inserted" from "deleted" (both absent), the stock table is
storage-queue-owned and unreadable from the fork queue, and REPLACE-on-insert
resurrects even if the observer is ordered first. A correct fix needs a
migration-scoped deletion **tombstone** that survives to be rechecked at insert
time. Bounded and left documented rather than built: migration is one-time per
account on first upgrade (idempotent, `accountInited`-guarded), the window is
milliseconds on that first start, and the resurrected row stays a normal deletable
held row -- no leak, loss, or crash. Belongs to issue #346.

*(Established 2026-09-11, `#ghost-hold`, PR #347 round-2 review. Contested the
prescribed fork-queue recheck as not race-free without a tombstone; documented as
an accepted #346 limitation instead.)*

## Ghost Hold: logout purge cannot run on a doubly-broken teardown

`GhostHoldStore.deleteDatabaseFileOnQueue` (`GhostHoldStore.java:490` onward)
invalidates a logged-out account's held rows by purging them in place, then
unlinking the file, so a same-user relogin inherits nothing (`enforceOwner`
only purges on an owner *mismatch*, and a same-user reopen is not one). That
holds as long as *either* the in-place purge *or* the unlink succeeds. It does
not on the doubly-rare case where the DB is both unopenable (the on-demand
`db()` at `GhostHoldStore.java:502` throws, so the purge block is skipped) *and*
undeletable (the unlink then fails on a WAL/SHM lock or permission). On that one
path the owner stamp and rows survive on disk and a same-user relogin can reload
them. Closing it properly needs an out-of-band tombstone that forces a purge on
the next open regardless of owner -- storage-redesign territory the safety
bundle scopes out, and doubly rare on top. Accepted and documented rather than
fixed. (Separately, the held-count refresh in
`GhostModeActivity.refreshHeldCount` (`settings/GhostModeActivity.java:120`) is
an async `countHeld` callback fired from `onResume`; a stale count can flash for
one frame after a rapid resume -- below the severity floor, recorded, left as
is.)

*(Established 2026-09-10, `#ghost-hold`, ghost-hold-audit branch superseding
PR #336.)*

## `squash_merge_commit_message: COMMIT_MESSAGES` plus an un-overridden squash message is what keeps `#slug` tags alive on `dev` — and no CI check guards either

The repo lands PRs by **squash merge** (`allow_merge_commit: false`, `allow_squash_merge: true`, 2026-09-10). A squash writes one new commit onto `dev` and discards the PR branch's commits — the very commits `commit-tag.yml` validated. So whether the `#<slug>` tag reaches `dev` at all rests on **two** things, not one: the `squash_merge_commit_message` setting **and** the squash message being left at its default rather than overridden at merge time. With `COMMIT_MESSAGES` (the current, correct value) GitHub builds the *default* squash body from every branch commit's message — **including each commit's subject line, rendered as a `* <subject>` bullet** — so a tag that lives only in a commit *subject* still lands in the squash body and survives (verified 2026-09-10). But that is only the default: flipping the setting to `PR_BODY` or `BLANK`, **or** overriding the body at merge time (the merge UI's editable message, or `gh pr merge --body`/`--subject` — see the merge-command note in `.claude/skills/nagramx-branch-flow/SKILL.md`), can drop the tags, so a later merge **can** land a tag-less commit on `dev`. **No CI check catches this** — `commit-tag.yml` runs against the PR branch, which was tagged; it never sees the squash GitHub writes afterward. The failure is silent and permanent in the `dev` log.

What the `dev` log actually shows (2026-09-10):

- **Only three of the last 25 merged PRs were squash-merged at all**: #338 (11 commits, tags **preserved**), #335 (12 commits, tags **preserved**), and #334 (10 commits, tags **lost**). Every other PR in that window landed as a **merge commit** and is tag-exempt, so the squash path is the only one that can silently drop a tag.
- **All three squashes were multi-commit, so commit count is not the discriminator.** #334 did **not** lose its tag for being "a single-commit PR whose body omitted it" — `refs/pull/334/head` has **10 commits, every one carrying `#docs` in its subject**. The squash nonetheless landed as `becfe09f63` with subject = the PR title (`Carry a stalled session's outstanding authorized work into its replacement (#334)`) and a **completely empty body**; its only hashtag is the bare PR number `#334`, which is explicitly not a `#<slug>` tag, so `git log --grep '#<slug>'` will never find it. #335's `599baff6ee` and #338's squash both kept their `#<slug>` tags in the concatenated body via `COMMIT_MESSAGES` — #335's subject `remind at typing time that ghost mode doesn't cover sending (#335)` is itself untagged, but the `* <subject>` bullets in its body still contain `#ghost-type-warning`.
- **Why #334's body came out empty is unresolved — two candidate causes, and the evidence does not decide between them.** Either (a) `squash_merge_commit_message` was **not yet** `COMMIT_MESSAGES` when #334 merged, or (b) the squash message was **hand-edited or cleared at merge time** — via the merge UI's editable message **or** a `gh pr merge --body`/`--subject` override — for that one PR. Merge times: #334 `02:46:55Z`, #335 `02:54:25Z`, #338 `03:26:17Z` — #334 landed its empty body **eight minutes before** #335 landed with its tags intact, which fits either a setting corrected in that window or a manual edit on #334 alone. **Do not pick one.** A future reader who "fixes" this by mandating the tag in the PR *body* would be chasing the wrong cause: subject-only tags already survive under `COMMIT_MESSAGES` today, per the `* <subject>` behaviour above.

Two consequences worth stating. First, `commit-tag.yml` passing on a PR is **not** evidence that what lands on `dev` is tagged — the tag survives the squash only when the setting is `COMMIT_MESSAGES` *and* the message is left un-overridden, which is why the *Land a change* preflight in `.claude/skills/nagramx-branch-flow/SKILL.md` stop-and-reports if `squash_merge_commit_message` is not `COMMIT_MESSAGES` and its merge command passes neither `--body` nor `--subject`. Second, `squash_merge_commit_title: PR_TITLE` makes the **PR title the permanent `dev` commit subject**, so a vague or AI-referencing title is a permanent defect in the log, not cosmetic.

The branch that produced the squash is auto-deleted (`delete_branch_on_merge: true`), but its full pre-squash range is **not** lost: `refs/pull/<N>/head` is permanent and survives the deletion. Verified — #335's branch `2026-09-10-ghost-type-warning` is gone from `origin`, yet `git fetch origin refs/pull/335/head` still returns tip `6465b2fda8` (12 commits, all tagged). That ref is the recovery path for a range you need after the branch is gone. Verified with `git show -s --format='%B' becfe09f63` / `599baff6ee`, the `refs/pull/334/head` and `refs/pull/335/head` commit counts (10 and 12), and the `refs/pull/335/head` fetch on `origin` (2026-09-10).

*(Established 2026-09-10, #docs.)*

## Stock deletion notifications carry no producer-session identity

A fork observer that keys off `sessionEpoch` (or the store generation) at the
moment a stock `messagesDeleted` notification is *delivered* is pinning the
wrong session whenever that delivery is delayed across a logout. Stock's
deletion notification carries only the deleted ids, no token identifying which
session produced the delete, so there is nothing at delivery time that can tell
"this delete belongs to the user who is now logged in" apart from "this delete
belongs to the user who logged out while it was in flight."

Where it bites in this fork: `GhostHoldObserver.didReceivedNotification`
handles `messagesDeleted` (`GhostHoldController.java:1937`) and captures its
session at delivery -- `final int deleteSession = sessionEpoch.get(account)`
(`GhostHoldController.java:1963`) -- then hops to the fork queue via
`store.runOwned(...)`, which pins the store generation at post time
(`:1965`, `GhostHoldStore.java:113-137`). `appDidLogout` bumps `sessionEpoch`
synchronously (`GhostHoldController.java:1918-1927`) and enqueues the store
teardown that advances the generation. Both guards therefore read *delivery*-
time state. A stock `messagesDeleted` for a negative (local) id that is posted
before logout but delivered after a full logout + teardown + relogin (stock can
defer a post to the UI-thread handler) is delivered post-relogin, so both
guards sample the *new* session and pass. `UserConfig.clearConfig()` resets
`lastSendMessageId`, so the reused slot mints the same negative ids, and
`selectOnQueue(mid)` (`GhostHoldStore.java:441`) can match a genuine held row
of the new user -- which `deleteManyOnQueue` (`GhostHoldController.java:1978`)
then deletes, and `postScheduledCount(account, d, deleteSession)` (`:1980`)
publishes against.

Consequence: the new session's held row is deleted. This is the one member of
the logout/relogin teardown-race family that fails **unsafe** -- it destroys a
row rather than the family's usual safe direction of leaving rows held (nothing
sent, nothing leaked). Bound: a very narrow window (a deletion post delayed
across an entire logout/relogin), and the loss is a local held draft only --
never a message that was sent, and nothing leaks to the server or to the wrong
account's transport.

Why no local fix exists -- three refutations, so this is not re-litigated:
1. A **delivery-time recheck** of `sessionEpoch` reads only the new session
   (session B); it is "too late" because delivery already happened under B, so
   the recheck passes.
2. **Recording the owning user on the row and comparing** does not discriminate
   either: the row `selectOnQueue` finds genuinely belongs to session B (the new
   user reused the same negative id), so an owner check confirms rather than
   rejects the delete.
3. A **fork-owned monotonic id space** to avoid the collision would risk
   colliding with stock's own negative ids for real unsent messages.
   The only sound fix is a producer-time session/generation stamp carried from
   stock `MessagesController` / `MessagesStorage` into the notification -- a
   cross-file storage-lifecycle change, i.e. the same observable "store ready /
   producer token" primitive #346 already scopes out. Not patchable at the
   observer.

Filed as #349, scoped alongside the #346 "store ready" work; part of the
logout/session-teardown-race family with #343 and #346.

*(Established 2026-09-11, #ghost-hold.)*

## The MODE_SCHEDULED sort collapses to input order for held rows — and its id tie-break is inverted for local ids

The stock scheduled-list sort in `MessagesController.processLoadedMessages` — the
upstream original that the `#ghost-hold` load override (below, `:12401-12415`)
replaced — was:

```java
if (o1.messageOwner.date == o2.messageOwner.date && o1.getId() >= 0 && o2.getId() >= 0) return o2.getId() - o1.getId();
return o2.messageOwner.date - o1.messageOwner.date;
```

Two traps live here for Ghost Hold's held rows. Any group of held rows that
**share an identical date** trips them, because held rows carry negative local
ids. That covers three cases: the **plain-hold sentinel** `GHOST_HELD_DATE_SENTINEL`
= `0x7FFFFFFD` (`GhostHoldController.java:76`, applied at `:532`); the
**send-when-online sentinel** `0x7FFFFFFE`, which `persistHeld` stores verbatim
(`GhostHoldController.java:532`) for a held "send when online" and which is likewise
a shared literal, not a real timestamp; and **two timed holds that fall in the same
wall-clock second**. A held row whose date is genuinely distinct from every other
row's re-sorts to its own date position and neither trap touches it — the traps are
about the *tie*, not about any one sentinel value.

1. **The tie-break never fires between same-date held rows.** The `getId() >= 0` guard
   excludes negative local ids, so for any two held rows on the same date the comparator
   falls
   through to `o2.date - o1.date`, which is `0` — they tie. `Collections.sort`
   is a documented-stable sort, so the on-screen order of each tied run is
   decided entirely by the order they were fed in, i.e. the append order in
   `GhostHoldController.injectHeldScheduled`. The fork no longer leaves that to
   stable-sort accident: the `#ghost-hold` load fix **overrides this comparator**
   (`MessagesController.java:12401-12415`, using an immutable `HeldOrderView`
   snapshot rank built once at `:12400` — never per comparison, which would make
   the sort O(n²log n)) so a tie that *involves* a held row is resolved by
   flush-snapshot rank instead of append order, and an already-present held member
   re-shown on reload (e.g. an `0x7FFFFFFE` "until online" hold) is ranked too
   rather than left to a stable-sort accident. The stock trap still bites anyone
   who removes that override — an upstream bump that made the comparator start
   tie-breaking held rows would silently revert the load ordering with no compile
   error and no symptom visible without a device — so the override, not the stable
   sort, is now what makes the load order deterministic.

2. **Dropping the `getId() >= 0` guard does NOT fix it — it inverts it.** Local
   send ids *decrement* (`UserConfig.getNewMessageId`), so a later hold has a more
   negative id. `o2.getId() - o1.getId()` is descending id, which for decrementing
   ids puts the **oldest** hold first (index 0 = screen bottom) — reproducing the
   exact reversed order, now baked into one of the hottest base files. The
   tempting one-line comparator "fix" is wrong, not merely fragile.

## MessagesController's load sort and ChatActivity.processNewMessages are two independent orderings of the same scheduled list

A held row reaches the Scheduled list two different ways, ordered by two different
pieces of code — a fix to one does nothing to the other:

- **Load path:** open the Scheduled list → `MessagesController.processLoadedMessages`
  calls `GhostHoldController.injectHeldScheduled` (`MessagesController.java:12385`)
  then the fork-overridden scheduled comparator (`:12401-12415`).
- **Live path:** a successful hold publishes the row via
  `updateInterfaceWithMessages(peer, obj, 1)` (`GhostHoldController.java:667`) →
  `didReceiveNewMessages` → `ChatActivity.processNewMessages`'s own placement loop
  (`ChatActivity.java:27981-28112`). That loop breaks on `lastMessage.date < obj.date`
  or `date ==` with both ids `> 0`; for same-date-tied negative-id held rows neither
  branch can fire *between held siblings*, so the loop cannot order a live-arriving
  hold against the existing held rows — it stops at the first lower-dated row (a real
  scheduled row or date header) or falls through to `messages.size()`, and either way
  the block ends up newest-first unless a hook forces otherwise.

Both mechanisms produce the identical reversed block by different means, so any
change to held-row display order must account for both. The `#ghost-hold`
scheduled-order fix routes both through **one ordering contract** — a held row's
rank is its position in the flush snapshot (`cachedForDialog()`, oldest-first), and
because the list is reverse-stacked a higher rank (newer hold) must land at a lower
array index (screen bottom = oldest-at-top). Rank is exposed by an immutable
`GhostHoldController.HeldOrderView` (`GhostHoldController.java:1653`, built by
`heldOrderView`, `:1675`), applied per tying-date group so the plain (`0x7FFFFFFD`)
and online (`0x7FFFFFFE`) buckets order independently and a genuinely-distinct-dated
row is left to the stock date sort. The two adapters differ only in substrate:
- **Load** (`GhostHoldController.injectHeldScheduled`, `:1500`) appends the whole
  snapshot to the pre-sort `objects` list newest-first (descending rank) and lets the
  overridden comparator settle the ties (see the override above) — held sorts above a
  genuine same-date row.
- **Live** (`GhostHoldController.placeLiveHeldRow`, declared `:1731`, called once from
  `ChatActivity.java:28122`) computes the arriving row's insertion index directly from
  its rank relative to the rows already on screen: below any older held sibling, above
  any newer held sibling, and on the message side of the day header. It self-gates on
  `chatMode == MODE_SCHEDULED` (defence in depth behind the stock scheduled filter; see
  the MODE_SAVED trap below) and on the row being a held member of the snapshot (a
  missing member fails closed to stock placement, never to comparing ids). It orders
  held rows only; it does **not** move a genuine same-date row, so a genuine same-date
  row can render on a different side of the held block live vs cold — an accepted
  residual, documented below.

*(Established 2026-09-11, #ghost-hold. Generalised 2026-09-12 from the plain
sentinel to any tied-date held group; live path rebuilt onto the shared
HeldOrderView oracle the same day.)*

## The Scheduled "until online" date header carries different dates on the load and live paths — match it by dateKey, never by numeric date

The Scheduled list builds its per-day date header in two places, and they disagree
on the header's `date` for a send-when-online day:

- **Load path** (`ChatActivity.java:23241-23262`): for `MODE_SCHEDULED` with
  `obj.messageOwner.date == 0x7ffffffe` it stamps the header `dateMsg.date = 0x7ffffffe`
  (`:23253`); otherwise it computes Calendar midnight of the row's day.
- **Live path** (`ChatActivity.java:28210-28227`): it sets the "until online" *label*
  for `0x7ffffffe` (`:28212`) but has **no** matching date branch — it unconditionally
  runs the Calendar midnight computation (`:28227`).

So the same "until online" header carries `0x7ffffffe` when cold-loaded and a midnight
timestamp when created live. Any code that pairs a message with its header by
`header.messageOwner.date` will therefore pass on cold load and silently fail live.
Match the header structurally instead — `isDateObject` AND NOT `isVideoConversionObject`
(a video-conversion "processing" row also sets `isDateObject`; see the `isDateObject` entry
below for why it must be excluded here) plus the day-granular `dateKey`
(`MessageObject.java:1964`, computed from `Calendar.DAY_OF_YEAR`, so `0x7FFFFFFD`,
`0x7FFFFFFE` and that day's midnight all share one `dateKey`). `placeLiveHeldRow`
does exactly this to keep a held row on the message side of its header.

The asymmetry also feeds the live insertion loop's id tie-break (next entry): a date
header is a `MessageObject` with id `0`, which fails that loop's `> 0` test, so a
same-date row can *skip the header itself*. How far it skips differs by which build
path made the header — a load-built "until online" header carries `0x7ffffffe` and
ties the row's date (the row skips it and travels further up), while a live-built one
carries midnight (lower, so the row stops just past it). So header-relative live
placement of a genuine row is not even uniform across cold-load vs live-arrival.

*(Established 2026-09-12, #ghost-hold.)*

## The live Scheduled insertion loop is blind to negative-id rows — an accepted genuine-vs-held residual in the online bucket

The stock live insertion loop in `ChatActivity.processNewMessages`
(`ChatActivity.java:28090`) only tie-breaks two equal-date rows when **both** carry
ids `> 0`. Ghost Hold's held rows carry negative local ids, so they are invisible to
that tie-break — the live-path twin of the load comparator trap at the top of this
file. This has a consequence the `#ghost-hold` fix deliberately does **not** remove:

A genuine same-date row the user scheduled — most obviously a "send when online"
message on date `0x7FFFFFFE` — meets the same-date held run and satisfies **neither**
the loop's id tie-break **nor** a lower-date break (the held rows share its date), so
it skips the entire held run and — via the header id-`0` skip above — can skip the day
header too, landing **above** the held block. This is **not** limited to positive-id
genuine rows: `SendMessagesHelper` assigns a fresh local **negative** id before
applying `scheduleDate`, and a user-scheduled local media or unsupported-text send is a
genuine row that keeps that negative id, so it too fails the loop's `> 0` tie-break and
hits the same split. (A positive-id genuine row finds the held rows' ids negative; a
negative-id genuine row fails the `> 0` test itself. rankOf still returns -1 for it —
it is not a held mid — so `placeLiveHeldRow` leaves it in stock placement either way.)
The cold-load override (`MessagesController.java:12401`) instead sorts a genuine
same-date row **below** the held run. So one genuine send-when-online row can sit on
**opposite sides** of the held block live vs cold.

The live adapter (`placeLiveHeldRow`) places held rows only and must not move a
user-scheduled row, so this divergence is **accepted**, not fixed: reaching parity
would require moving a genuine row the user scheduled, which is out of scope. It is
cosmetic — no send-order, data, or crash impact — affects only the `0x7FFFFFFE` and
same-second timed-hold buckets (the only ones that can share a date with a genuine
row), and **self-corrects on the next reload**, which runs the deterministic
override.

Crucially, the **plain-hold bucket** (`0x7FFFFFFD`) has **no** genuine counterpart —
a user cannot schedule a message onto the plain-hold sentinel date — so its held run
is correct on **both** paths. That is the bug dazewell reported (holding 1, 2, 3
rendered 3, 2, 1) and it is **fully fixed**; the residual above is a different,
pre-existing, rarely-hit case, not the reported bug.

*(Established 2026-09-12, #ghost-hold.)*

## `isDateObject` does not mean "day header" — a video-conversion row wears the same flag and can share an online-held row's exact date

Header-detection that keys only on `isDateObject` is a trap, because in the Scheduled
`messages` list that flag has **two** producers, not one. Enumerating every
`isDateObject = true` assignment that can reach this list:

- **Day header, load path** — `ChatActivity.java:23266` (`isDateObject = true`; the
  paired `type = TYPE_DATE` is at `:23264`).
- **Day header, live path** — `ChatActivity.java:28231` (`isDateObject = true`; the
  paired `type = TYPE_DATE` is at `:28229`).
- **Video-conversion "processing" row** — `ChatActivity.java:23384`, which also sets
  `isVideoConversionObject = true` and, for a `video_processing_pending` send-when-online
  video, `dateMsg.date = 0x7FFFFFFE` (`:23370-23371`) — the **exact date and `dateKey`**
  of an online-bucket held row. It is a content-side marker, not a boundary.

The other `isDateObject` producers never reach a Scheduled list: the discussion-thread
header (`ChatActivity.java:22966`) is thread-mode only, the filtered-search header
(`:10368`) targets `chatAdapter.filteredMessages`, the channel-admin-log header
(`MessageObject.java:2092`) is the admin-log screen, and `SharedMediaLayout.java:9239`
is the shared-media grid. So **`isVideoConversionObject` is the sole non-day-header
`isDateObject` producer in this list** — excluding it is the complete rule, not a
patch over one instance.

Consequence for anything positioning a row against "the header": a walk that treats
every `isDateObject` row as the boundary will, for a send-when-online video, both miss
the conversion row in a same-date content scan and — if it keeps the last matching
`dateKey` — anchor a boundary clamp to the conversion row instead of the real header,
which can push a row somewhere neither cold nor live would put it. `placeLiveHeldRow`
guards with `isDateObject && !isVideoConversionObject` so only a true day boundary is a
boundary; the conversion row falls through as an ordinary (id-`0`, `rankOf` -1)
below-row, matching where cold load leaves it (beside its video, below the held run).
The load comparator is unaffected: date headers and conversion rows are added by
`ChatActivity` *after* `MessagesController.java:12385` runs, so the comparator never
walks them.

*(Established 2026-09-12, #ghost-hold.)*

## Ghost Hold: `updateStateOnQueue` re-puts an existing key so its `master` position survives a state change, while `insertOnQueue` remove-then-puts so it appends

`GhostHoldStore.master` is a `LinkedHashMap<Integer, HeldRecord>`
(`GhostHoldStore.java:78`), and its **iteration order is the one source of both orderings
that matter** for Ghost Hold: the flush/send order (`selectByStateOnQueue` iterates
`master.values()`, `GhostHoldStore.java:424-428`) and the Scheduled-list rank (`publish()`
rebuilds the per-dialog snapshot by iterating `master.values()`,
`GhostHoldStore.java:307-317`, which `cachedForDialog` hands to `heldOrderView` — rank is
snapshot position). So anything that changes `master`'s iteration order silently changes
both what the user sees and what order the messages send in.

The two mutators touch that order differently, and the difference is load-bearing:

- `insertOnQueue` (`:320`) does `master.remove(rec.mid)` **then** `master.put(rec.mid, rec)`
  (`:339-340`). On a `LinkedHashMap` a remove-then-put moves the key to the **end** of
  iteration order — a new hold appends, which is what makes a freshly held message the
  newest (highest rank).
- `updateStateOnQueue` (`:350`) does a bare `master.put(mid, old.withState(newState))`
  (`:357`) with **no** preceding remove. A `LinkedHashMap` in its default (insertion-order,
  not access-order) mode does **not** reorder on a re-put of an existing key, so the record
  keeps its position.

Why it matters here: a HELD→FLUSHING→HELD round trip (flush claims a row, then reverts it —
`revertToHeld` / `completeHandoff` / startup reconcile) goes through `updateStateOnQueue`
both ways, so the reverted row lands back in its **original** flush position, not at the end.
Had the revert gone through an insert-style remove-then-put, a row that briefly flipped to
FLUSHING and back would jump to newest and both invert its send order and reorder its
Scheduled-list rank. The live-adapter fix that classifies a rankless (transiently-FLUSHING)
held row as an older sibling (`GhostHoldController.placeLiveHeldRow`) leans on this: it is
only safe to assume such a row is older because its position — and therefore its rank once it
reverts — is preserved across the state flip. If a future change ever routes a state update
through a remove-then-put, or flips `master` to access-order, that assumption breaks silently.

*(Established 2026-09-12, #ghost-hold.)*

`ChatActivity.didReceiveNewMessages` (`ChatActivity.java:24096-24115`) guards the
mode-mismatch case with:

```java
if (mode != chatMode && chatMode != MODE_SAVED && chatMode != MODE_SUGGESTIONS) {
    ...
    return;
}
...
processNewMessages(arr);
```

The `return` is **skipped when `chatMode == MODE_SAVED`** (or `MODE_SUGGESTIONS`),
so an event published with `mode == MODE_SCHEDULED` (`1`) still falls through to
`processNewMessages` while the open fragment is the Saved-messages timeline. Ghost
Hold publishes a successful hold via `updateInterfaceWithMessages(peer, obj, 1)`
(`GhostHoldController.java:667`), and a hold sent from Saved Messages is holdable, so
its live row does reach `processNewMessages` there. But the real boundary that keeps a
Saved-Messages hold out of the Scheduled placement is one level lower: the per-message
scheduled filter at `ChatActivity.java:27958`
(`if (obj.scheduled != (chatMode == MODE_SCHEDULED)) continue;`) drops a held row —
published with `scheduled = true` (`GhostHoldController.java:664`) — on any
non-Scheduled timeline **before** the `#ghost-hold` `placeLiveHeldRow` hook (called at
`ChatActivity.java:28122`, downstream of that filter) is reached. So a held row cannot
in fact arrive at that hook in `MODE_SAVED` today. The hook still gates on
`chatMode == MODE_SCHEDULED`, but as **defence in depth**, not as the boundary: it
guards a *future* hook added inside `processNewMessages` on a path that keys only on
the message (a held row, a sentinel date) and bypasses the `:27958` filter — that one
*would* fire on the ordinary Saved timeline. The trap for the next person is that
reaching `processNewMessages` in `MODE_SAVED` is real, but reaching this PR's hook with
a held row is not; do not conflate the two.

*(Established 2026-09-12, #ghost-hold.)*
