# Dead ends

Hypotheses that were investigated and **disproven**, with the evidence that
killed them. Recorded so the next investigation doesn't spend time re-testing
a theory that's already dead. Re-verify the citation before relying on it —
see the README.

## "The enabled launcher activity-alias drives the ColorOS notification icon"

Disproven on the tested ColorOS device, for the pre-change manifest. Selecting
the Neon launcher icon in Chat Settings flips the enabled activity alias
through `LauncherIconController.setIcon` (`LauncherIconController.java:27-34`),
called from the picker's tap handler at `AppIconsSelectorCell.java:136` — not
from the unrelated `tryFixLauncherIconIfNeeded` startup safety net
(`LauncherIconController.java:11-19`), which only runs at app launch to catch
a state where no alias is enabled at all. Selecting Neon changed the
home-screen icon as expected. But the ColorOS notification stayed on the
Telegram-blue paper plane rather than following Neon. Notifications never read
an activity-alias icon in the first place: `NotificationsController` derives
its small icon purely from `NaConfig.notificationIcon` via
`getNotificationIconResId()` (`NotificationsController.java:6531-6545`), and
both `.setSmallIcon(...)` call sites (`:4703`, `:5761`) call that method, not
any launcher-icon or activity-alias lookup. Before this branch's change, the
`<application>` node's own `android:icon`/`android:roundIcon` still pointed at
the Telegram-blue mipmaps (`ic_launcher_nagram_blue`/`_round` — see the "before"
side of commit `cc2b3e0786` in this PR, since this PR's final state replaces
those literal resource refs with a per-variant `${fixedAppIcon}`/
`${fixedAppIconRound}` manifest placeholder — see the "Positive result"
paragraph below — and a plain current-tree line citation would no longer be
reverifiable), so the
enabled-alias theory and the fixed-application-icon theory were
indistinguishable from this evidence alone — both pointed at the same blue
asset. This entry only kills the activity-alias theory.

**Positive result (post-smoke).** On dazewell's tested ColorOS device, the
Unofficial (`nekox.messenger`, DazegramX) smoke build from PR #291 head
`a6938cde4a` was installed with the Neon launcher alias still selected in Chat
Settings. That build's `<application>` node carried a direct
`android:icon="@mipmap/ic_launcher_nagram"`/`android:roundIcon="@mipmap/ic_launcher_nagram_round"`
(no placeholder yet at that head). Triggering a new-message notification
showed the Default/orange icon art while the home-screen launcher icon
remained Neon. With only those two attributes repointed from the
Telegram-blue mipmaps to the Default ones (`#app-icon-fallback`) and nothing
else changed, this confirms — for this device and mode — that the fixed
`<application>` icon, not the enabled launcher activity alias, is the source
ColorOS reads for the notification icon. Evidence is visual-only; no device
trace was captured, so this is tested-device evidence, not a general claim
about ColorOS behavior across other versions, OEM skins, or notification
configurations. This result still applies after the later variant-scope
correction: that change replaced the literal resource with a
`${fixedAppIcon}`/`${fixedAppIconRound}` manifest placeholder resolved per
package variant (`build.gradle` `manifestPlaceholders`), and for Unofficial
that placeholder resolves to the same `ic_launcher_nagram`/`_round` pair the
smoke build already tested — Unofficial's merged manifest icon attributes are
unchanged, byte-for-byte, by that correction. This entry says nothing about
Official (`org.telegram.messenger.beta`, Dazegram), which keeps its
pre-existing Blue fixed icon and was not part of this investigation.

*(Established 2026-09-04, confirmed 2026-09-05.)*

## "The notification icon can be made to follow the Chat Settings > App Icon selection at runtime"

Disproven, on three independent grounds — the strongest of them
device-confirmed — that together mean this does not extend or contradict the
two entries above; it answers the follow-up question of whether the
existing per-alias mismatch could be fixed dynamically rather than by picking
a single fixed icon.

**Reason A — on the tested ColorOS device, on the new-message notification
path, the supplied small icon does not appear to render.** Two things were
directly **observed** on that device, each scoped to that path — this is not
generalized to calls, silent, grouped, media, or ongoing notifications,
other ColorOS versions, or other OEM skins, consistent with the evidence
disclaimer in the entry above:
1. With the Neon launcher alias enabled, a new-message notification rendered
   the fixed `<application>` icon, not the alias icon (the entry above).
2. Changing Nagram Settings › General › Notification Icon
   (`NaConfig.notificationIcon`, the `ConfigCellSelectBox` at
   `NekoGeneralSettingsActivity.java:228-232`) from its then-current value to
   one visibly different option, restarting the app, and sending a message
   produced no visible change in the status bar or shade. Only that one
   before/after pair was exercised — not all four values individually.

**Inferred** from those two observations, on this path and this device only:
the value `getNotificationIconResId()` computes and passes to
`.setSmallIcon(...)` at `NotificationsController.java:4703` and `:5761` does
not appear to be rendered, and an application-level icon
(`ApplicationInfo.icon`) is shown instead. This is an inference from two
visual tests, not a platform mechanism confirmed by a device trace — no
`setSmallIcon` rejection or fallback path was observed directly. It is
nonetheless sufficient to make the feature unbuildable on this path/device
combination, independent of everything below: a feature whose entire
observable effect does not appear on the device that motivated the request
is not a feature there, whatever else it might do elsewhere or on other
paths.

**Reason B — the OEM shade icon this fork can actually influence is
immutable at runtime.** The confirmed source on the tested ColorOS device is
`ApplicationInfo.icon`, a resource id baked into the manifest and resolved by
`system_server` from the app's parsed resource table, re-read only at
install/update — there is no public API that mutates it post-install. Each
runtime candidate considered, and why it's dead:
- **RRO / `OverlayManager`** — requires the overlay be signed with the
  platform key or preinstalled as privileged; not available to a normally-
  signed app, and no runtime-resource-override code exists anywhere in this
  tree.
- **`setSmallIcon(Icon)` with full-colour art** — since API 21 the platform
  composites the status-bar/notification small icon from its **alpha channel
  only**, discarding colour; `minSdk` is 27 (root `build.gradle:36`,
  established in the entry above), so no supported version behaves
  differently. On this
  device and path the point is moot regardless, per Reason A: the evidence
  suggests the argument to `setSmallIcon` isn't rendered here.
- **`setLargeIcon`** — the one non-monochrome notification surface, already
  occupied by the conversation avatar
  (`NotificationsController.java:4737`, `:4741`, `:4751`, `:5838`). Repurposing
  it for the app icon would mean losing the avatar, a downgrade not a fix.
- **`NotificationChannel`** — carries no icon of any kind.
- **`Person` / bubble / conversation icons** — these show the contact's
  avatar by design; they never surface the app's own icon.
- **Resource-qualifier trickery** (e.g. a `mipmap-night/` variant so the
  `ApplicationInfo.icon` drawable resolved at parse time differs) —
  considered and rejected explicitly: the app has no way to force system
  night mode on demand, this yields at most two resolved states rather than
  the picker's fifteen, and which qualifier ships is OEM-dependent.
- **Per-alias `<activity-alias>` icons** — already disproven on-device by the
  entry above; notifications never read that value at all.

**Reason C — no notification-legal art exists for the picker's icons, even
setting Reasons A and B aside.** Limiting scope to just the monochrome status-bar
small icon Android actually composites, there is nothing in the tree to map
the 15-entry `LauncherIconController.LauncherIcon` picker
(`TMessagesProj/src/main/java/org/telegram/ui/LauncherIconController.java:36-51`) onto:
- Of those 15 entries, 9 (`DEFAULT`, `GOOGLE`, `COLORFUL`, `DARKGREEN`,
  `NEON`, `NIELLO`, `BLUE`, `DARKBLUE`, `BLURBLUE`) resolve to adaptive-icon
  XML under `mipmap-anydpi-v26/` that **all** reference the same monochrome
  layer, `@drawable/ic_launcher_nagram_monochrome` — confirmed by grep across
  every file in that directory. That drawable is not even one stable asset:
  it exists only per package-variant source set, at
  `TMessagesProj/src/iconOfficial/res/drawable/ic_launcher_nagram_monochrome.xml`
  and `TMessagesProj/src/iconUnofficial/res/drawable/ic_launcher_nagram_monochrome.xml`
  separately (no `src/main/res/drawable/` copy), each `108dp`/`512`-viewport
  with a distinct path.
- 1 entry (`TELEGRAM`, `ic_launcher_dr.xml`/`_round`) references
  `@drawable/icon_plane` (`TMessagesProj/src/main/res/drawable/icon_plane.xml`,
  `90dp`/`90` viewport).
- The remaining 5 entries (`VINTAGE`→`icon_6_launcher`, `AQUA`→`icon_4_launcher`,
  `PREMIUM`→`icon_3_launcher`, `TURBO`→`icon_5_launcher`,
  `NOX`→`icon_2_launcher`) have **no `<monochrome>` element at all** — confirmed
  by the same grep returning zero matches for any of those five files.
- Net: 2 distinct shapes cover 10 of the 15 picker entries, and the other 5
  have nothing to map to. This also weakens the case for adding a "match app
  icon" mode on redundancy grounds, not just geometry: the existing
  `notificationIcon` setting's own value `0` is already labeled "Telegram"
  (`strings.xml:1575`, `R.drawable.notification`) and its default value `1`
  is already the nagram glyph. Even setting geometry aside, mapping the
  launcher picker's 9-entry "nagram-style" cluster and its 1-entry
  `TELEGRAM` slot onto notification art would land on shapes conceptually
  adjacent to icons this setting can already produce manually — it does not
  reach a state the four-option setting is currently unable to express.
- Geometry rules out using either of the two available launcher monochrome
  shapes as a notification icon even where one exists — this is a separate,
  independently-fatal point from the redundancy argument above, not
  contingent on it. Both `ic_launcher_nagram_monochrome` variants are
  `108dp`/`512`-viewport adaptive layers with a path spanning roughly
  x139→371 (Official) or x162→349 (Unofficial) — each only ~40-45% of the
  canvas, because adaptive icons reserve a safe zone around the mark.
  `icon_plane.xml` is `90dp`/`90`-viewport with a path spanning roughly
  x28→58, ~33% of its canvas. The real notification glyphs, e.g.
  `TMessagesProj/src/main/res/drawable-anydpi/nagram_notification.xml`, are
  `24dp`/`24`-viewport with art filling the box edge-to-edge. Pointing either
  candidate at `setSmallIcon` would render a visibly shrunken glyph inside a
  mostly-empty status-bar icon.

**Standing constraint for future disguise/privacy work.** Reason A narrows
the earlier framing of the launcher-alias disguise consequence to one
channel, and makes it absolute rather than merely "the shade keeps showing
the real icon": on this device, `ApplicationInfo.icon` is not just the icon
ColorOS *prefers* to show, it is the **only** icon channel ColorOS renders in
a notification at all — there is no secondary glyph channel to fall back to,
because the one Android API that exists for that purpose (`setSmallIcon`) is
confirmed inert here. A launcher-alias disguise hides the app on the home
screen and **cannot** hide it in the notification shade on this skin, with no
in-app mitigation available, because the channel it would need to use isn't
read. Any future disguise or customized-privacy feature on this fork should
treat that as a hard device-class limitation, not a bug to route around.

**Collateral fact, not a change request.** `NaConfig.notificationIcon` ships
four selectable options that are inert on the tested ColorOS device — picking
any of them changes nothing observable there. This is recorded as an observed
device limitation only. The setting is not fork-broken: it works as designed
on stock Android and (presumably) other OEM skins that honour `setSmallIcon`,
this is one OEM's shade behaviour, and this investigation does not propose
hiding, gating, or annotating the setting for it — that would be adding
device-specific UI for a single OEM, out of scope for this PR and not asked
for.

*(Established 2026-09-05.)*

## "Reuse `PollEditTextCell` for Send on event pattern rows"

Disproven. `PollEditTextCell` is a poll-specific, heavyweight composite with
emoji-button, checkbox animation, attach/move affordances, and poll-only
state machinery (`PollEditTextCell.java:63-89`). Pulling that class into the
event-schedule sheet would import upstream-fragile behavior and extra surface
the sheet does not need.

The `#eventschedule` editor instead reuses only the local rounded-field recipe
shape and builds a minimal row (`createPatternFieldRow`) in
`EventScheduleHelper.java:324-392`, then ports just the needed behavior
(IME-next/add/remove/focus transfer) at the sheet layer.

*(Established 2026-09-03.)*

## "Two independently-forwarded messages can coalesce into one group"

Disproven. `grouped_id` is only remapped onto a newly-sent message when the
**source** message already had a non-zero `grouped_id`
(`SendMessagesHelper.java:2498`). `MessageObject.getGroupIdForUse` is purely
field-driven — `localSentGroupId` if set, else `messageOwner.grouped_id`
(`MessageObject.java:7941-7942`) — with no date or adjacency fallback, and
`ChatActivity`'s group construction keys strictly on that value. The four call
sites that assign `localSentGroupId` either zero it
(`MessageSendPreview.java:1202`, `ChatActivity.java:10417`,
`ChatActivity.java:23105`) or copy an already-nonzero `grouped_id`
(`ChatActivity.java:24313`) — none of them can manufacture a shared group id
for messages that didn't already have one. Two originally-independent forwards
cannot merge into a group this way.

*(Established 2026-09-02.)*

## "The scheduled list groups messages by identical `schedule_date`"

Disproven. `MessageObject.GroupedMessages.calculate`
(`MessageObject.java:1326`, method start) lays out an already-formed group's
messages; it does not decide group membership from message dates, and no
date-based coalescing exists anywhere in the message-list build. Group
membership is `grouped_id`-only, per the entry above.

*(Established 2026-09-02.)*

## "Rescheduling a message races an unreconciled placeholder" — single-message path only

Disproven for the **single-message** reschedule/edit path, on reachability
rather than on the race itself. An outgoing, not-yet-reconciled message
(`id <= 0`, not a send error) resolves to `MESSAGE_TYPE_INVALID` in
`ChatActivity.getMessageType` (`ChatActivity.java:20672-20696`), and
`processRowSelect` refuses to select any row below `MESSAGE_TYPE_MEDIA`
(`ChatActivity.java:21294`) — so it can't be routed to Reschedule through the
UI in the first place for a directly-selected message. Had it somehow reached
`editMessage` anyway, the failure path shows an `EditMessageError` alert
(`AlertsCreator.java:450-457`); no such alert was observed during the
investigation that raised this theory, consistent with the path never being
reachable. See the matching latent-gap note in `upstream-traps.md`
(`canEditMessageScheduleTime` has no `id <= 0` guard) — the reachability guard
lives one layer up, in `ChatActivity`, not in that method itself.

This disproof does **not** extend to the bulk `RescheduleSpreadExecutor` path:
see "Bulk reschedule's album expansion bypasses the selection type check" in
`upstream-traps.md` for why a non-positive id can still reach that executor
through an album sibling, with reachability left unproven rather than
declared safe.

*(Established 2026-09-02, narrowed to single-message-only 2026-09-03.)*

## "A clickable child view swallowed the share-sheet avatar tap"

Disproven. `RecyclerListView.onInterceptTouchEvent` has a bail-out that skips
the row-select gesture when a clickable child sits under the tap point
(`RecyclerListView.java:1238-1248`), which is the obvious first guess for any
avatar-vs-label hit-test asymmetry. But neither `ShareDialogCell`'s avatar nor
its checkbox can trigger it: `BackupImageView extends View`
(`BackupImageView.java:38`) and `CheckBox2 extends View`
(`CheckBox2.java:18`), and neither sets `clickable`/`focusable` or overrides
`onTouchEvent`. `ShareDialogCell.java` itself has zero touch-handling methods,
and nothing under `org/telegram/ui` attaches a click listener to
`ShareDialogCell.getImageView()`. The real cause was the grid's
`allowSelectChildAtPosition` guard rejecting the tap outright — see
`upstream-traps.md`.

*(Established 2026-09-03.)*

## "The child-coordinate remap is wrong for the share sheet's multi-column grid"

Disproven. `RecyclerListView.onInterceptTouchEvent` remaps a touch into
child-local coordinates symmetrically on both axes —
`x = event.getX() - currentChildView.getLeft()`,
`y = event.getY() - currentChildView.getTop()` (`RecyclerListView.java:1234-1235`).
There's no column-index term in that remap, so it can't itself produce a
result that depends on which column (or row) was tapped. Not a source of the
top-row-only failure.

*(Established 2026-09-03.)*

## "A shared `#eventschedule` bulk trigger moves sibling messages when one is rescheduled"

Disproven. The untouched-trigger single-message edit path calls
`EventScheduleController.commitEditRefresh`, which delegates to
`EventScheduleStore.refreshFallbackForEdit`
(`EventScheduleController.java:545-559`) — that call writes only the edited
message's own `fallbackDate` and never issues an `editMessage` request for any
sibling id. The `scheduleRevision` counter it bumps on a real move
(`EventScheduleController.java:552-555`) is documented in the surrounding
comment as a process-local staleness token an in-flight **bulk arm** must
check against, not a mechanism that itself propagates a time change to other
messages (`EventScheduleController.java:526-544`). Arming the bulk trigger
also requires the user to explicitly open its controls (`armPending` /
`armExisting`, `EventScheduleController.java:308`, `:359`) — nothing arms it
implicitly as a side effect of an unrelated single-message edit.

*(Established 2026-09-02.)*

## "Spoiler atlas flicker is a coordinate-space mismatch between clip mapping and draw bounds"

Disproven. The atlas clip mapping and spoiler draw bounds are already
self-consistent: `applyClip` normalizes incoming bounds into atlas-local modulo
space and unions wrapped segments (`SpoilerEffectBitmapFactory.java:120-130`),
while the consumer draw path feeds current bounds directly into that same update
flow (`SpoilerEffect.java:323,329`). Particle admission itself is clip-relative
with an intentional +/-1dp damage margin (`SpoilerEffect.java:369-372,464`), so
the fix target is atlas lifetime/completeness, not coordinate remapping.

*(Established 2026-09-04.)*

## "Persistent clipped repaint can be made coherent by tuning clear/draw region"

Disproven. In the shared atlas producer, simulation advances by intersection on
spoiler-cell bounds (`SpoilerEffectBitmapFactory.java:96-104`) while trigger
regions are modulo-mapped and unioned (`SpoilerEffectBitmapFactory.java:120-130`)
and particle admission itself is clip-relative with a 1dp margin
(`SpoilerEffect.java:342,369-372,464`). Under clipped rasterization those three
surfaces cannot stay generation-coherent at mapped region seams: adjacent texels
inevitably come from different animation passes. Full-atlas redraw is required
for coherent published generations.

*(Established 2026-09-04.)*

## "Per-consumer ownership can make shared-atlas partial repaint coherent"

Disproven. The atlas trigger API carries only `Rect region` (`checkUpdate(Rect)`,
`SpoilerEffectBitmapFactory.java:112-118`) and merges into one shared union
(`SpoilerEffectBitmapFactory.java:120-130`), with no consumer identity channel.
Call sites are also content-agnostic view draws: one `DialogCell` can render
text spoilers and spoilered thumbs in the same draw pass
(`DialogCell.java:4416-4424,4842-4846`), and pinned-bar spoiler thumbs route
through a generic `BackupImageView` overlay (`ChatActivity.java:12765-12791`).
Ownership bookkeeping would still publish mixed generations unless atlas publish
is full-generation per pass.

*(Established 2026-09-04.)*

## "`startSpoilers` / `stopSpoilers` currently freeze and resume spoiler simulation"

Disproven. The lifecycle still posts start/stop notifications
(`ChatActivity.java:7548,7583`) and cells still forward them into
`setSuppressUpdates(...)` (`ChatMessageCell.java:401-420`,
`ChatActionCell.java:210-213`), but `SpoilerEffect` only stores that flag and
invalidates (`SpoilerEffect.java:105,159-161`) and the active draw/update path
does not consult it (`SpoilerEffect.java:317-329,342-507`). In this lineage the
chain is write-only; do not scope fixes around freeze-on-scroll behavior.

*(Established 2026-09-04.)*

## "Spoiler particles are the media/text privacy mask"

Disproven. Media masking comes from the blurred image layer inside the spoiler
clip (`ChatMessageCell.java:15363-15396`) and from the reusable blur receiver
path that is generated via stack blur then drawn as a separate layer
(`BackupImageView.java:104-108,361,470`). Particle noise is a decorative overlay
drawn after the blur layer (`ChatMessageCell.java:15395-15407`).

Text masking is also structural before particles are drawn: text is rendered
with spoiler rectangles clipped out (`SimpleTextView.java:1210-1218,1234-1248`),
and spoiler entities are carried by style runs (`ChatActivity.java:30998`,
`DialogCell.java:1926,2017`), so plaintext protection is not coupled to atlas
texture refresh cadence.

*(Established 2026-09-04.)*

## "Composer glass/default-wallpaper work (#287) or paid-media GroupMedia lifecycle caused deterministic spoiler-atlas flicker"

Disproven for this repro pair. The spoiler atlas path is centralized in
`SpoilerEffectBitmapFactory` and consumed via `SpoilerEffect` draw/update
(`SpoilerEffectBitmapFactory.java:26-47,146-168`; `SpoilerEffect.java:323,329`).
Composer glass/default-wallpaper code lives in `ChatActivityEnterView`
(`ChatActivityEnterView.java:1820-1868,5195-5196`) and paid-media lifecycle work
lives in `ChatMessageCell`'s `GroupMedia` branch
(`ChatMessageCell.java:9242-9251,24444-24445`): distinct subsystems, not the
atlas producer invariant that this fix changes.

*(Established 2026-09-04.)*

## "App-authored `android.util.Log.i` from a staging build can be read over `adb logcat` on dazewell's device"

Not observed, so not a reliable diagnostics channel on this device — treat it as
a dead end for on-device tracing until something proves otherwise. While bringing
up the wear-messages toggle we planted six `android.util.Log.i` markers in
`NotificationsController.showExtraNotifications` (a BEGIN liveness marker that is
the unconditional first statement of the method, plus the per-path markers). The
`org.telegram.messenger.beta` staging build carrying them was confirmed installed
and running at the exact head commit, and it was writing to logcat during the
window. Two bounded captures (`adb logcat -b main,crash`, ~150s each), the second
with a deliberate inbound message into a toggled-off chat that provably drives
`showExtraNotifications`, both returned **zero** matching lines — not even BEGIN.

The app-side causes were excluded. The reachability logic was correct: BEGIN is
unconditional at method entry, and its only caller `showOrUpdateNotification`
early-returns unless a push message or story is pending
(`NotificationsController.java:4158`), which explains a capture with no inbound
message but not the second one. R8 was excluded too: `proguard-rules.pro:173-176`
strips only `Log.v`/`Log.d` via `-assumenosideeffects`, so `Log.i`/`.w`/`.e`
survive the minified staging build. Separately, a shell-authored tag
(`adb shell log -t <tag>`) was visible in the same buffer, and an ART runtime
line tagged with the process name (`I .messenger.beta: Compiler allocated ...`)
was initially and wrongly read as proof app logging escapes — that is Android's
runtime logging, not app-authored output, so it proves nothing.

Suspected cause, not proven: the device is a OnePlus on ColorOS/OxygenOS, whose
ROM is known to filter logcat output from non-debuggable third-party apps, and
the staging build is `minifyEnabled=true` and release-signed
(`TMessagesProj/build.gradle:192-201`), so it qualifies for that filtering. The
takeaway for the next change: do not rely on `adb logcat` to confirm on-device
reachability of app-authored logs from a staging/release build on this device;
the wear-messages smoke gate was instead confirmed visually by dazewell. If
on-device log tracing is genuinely needed, a `debuggable=true` build (e.g. the
`debug` type) is the thing to try, not another `Log.i` capture from staging.

*(Established 2026-09-06.)*

## "The base fork preserved the encoder bitrate for force-muted GIF-panel sends"

Disproven -- it dropped the mute instead. During PR #300 review it was argued
that the new serialisation regressed edited GIF-panel sends by losing their
encoder bitrate, on the premise that `origin/dev` preserved it. Tracing the
full round trip disproves the premise. `ChatActivityEnterView.java:14813-14817`
force-sets `muted = true` on those sends, and such a record genuinely can carry
a positive bitrate (the `SELECT_TYPE_GIF` editor hides the mute button and
quality chip -- `PhotoViewer.java:15466`, `:15477` -- so `muteVideo` is false
and `PhotoViewer.java:10235` writes the tier bitrate). On the base fork
`getString()` wrote that positive bitrate into the legacy slot, so the very
next `parseString()` inferred `muted = false` and the forced mute was silently
discarded before the transcoder ever saw it -- the audio survived. The base
fork did not preserve bitrate *and* mute; it preserved bitrate *instead of*
mute. Anyone tempted to "restore the old behaviour" by writing a real bitrate
into the legacy slot for a muted record would be reintroducing that audio
leak. The fix taken in PR #300 keeps the legacy slot at `-1` and restores the
carried bitrate from the version-12 extension for any muted record with a
valid bool.

*(Established 2026-09-06, during the silent-video feature build, PR #300.)*

## "Our own settings page can use real Material Components / Material 3 widgets, since it's fork-owned"

Not viable under the fork's current dependency and theming architecture. Not a
proof of permanent impossibility — the classpath side has conceivable
workarounds — but a hard blocker to the naive "just add the Material AAR"
approach, on two independent grounds, so a "material 3 redesign" of any settings
page can't be dropped in out of actual MDC widgets without rearchitecting first.

1. Classpath. `TMessagesProj/build.gradle:250` globally excludes
   `androidx.recyclerview:recyclerview`, because the fork vendors a modified
   RecyclerView in-source (including the `ItemTouchHelper` these settings pages
   drag with). Material Components hard-depends on `androidx.recyclerview`, so
   either the exclude stays and MDC links against the vendored copy — whether
   that binds or breaks at runtime depends on whether the fork's modified
   RecyclerView kept ABI compatibility, which nobody has traced — or the exclude
   is lifted and two RecyclerView classes collide on one classpath. Separately
   and independently verified: `com.google.android.material.color.utilities` is
   ALREADY vendored in-source for `MonetHelper.java:11`, so pulling in the
   Material AAR is a duplicate-class D8/R8 failure at build time. That one is a
   reproducible build failure, not a prediction; it could in principle be worked
   around (exclude the utilities package from the AAR, or drop the vendored
   copy), which is exactly why this is "not worth it", not "impossible".

2. Theming. Making MDC widgets follow the app theme is the genuinely
   architectural blocker. The app's colours are runtime values resolved per
   frame from user-loaded `.attheme` data
   (`Theme.getColor(key, resourcesProvider)`); MDC theming resolves against
   `?attr/` resource values, which a bridge could only satisfy by pre-generating
   a theme overlay per palette — undefined for arbitrary user-supplied
   `.attheme` files, of which there is no fixed set. That is a real impossibility
   for the general case, not merely a cost. `DynamicColors`/Monet is not a
   counterexample: it builds its overlays from a FIXED set of FRAMEWORK resources
   (`android.R.color.system_accent1_*`, the very ones `MonetHelper.java:27-65`
   reads), not from the app's open-ended theme engine.

What the redesign actually wanted — the M3 grouped-card look — was already a
one-line call to `RecyclerListView.setSections()`, the house treatment used
everywhere else in Settings, with no MDC involved. See upstream-traps.md.

*(Established 2026-09-06.)*

## "Show a sub-floor packing thumb with a dimmed unavailable region" is not reachable fork-only

Tempting fix for issue #299: let the Icon spacing thumb sit at the real saved value (say 85%) even when the scale-dependent floor is higher, drawing the unreachable band below the floor dimmed, so the user sees their value is still there. It cannot be done without editing `SlideIntChooseView`/`SeekBarView`, because the dimmed band and the thumb clamp are the *same* call: `setMinValueAllowed` clamps `this.value` up to the minimum (`SlideIntChooseView.java:222-226`) and then calls `seekBarView.setMinProgress(...)`, which both draws the 50%-alpha unavailable band (`SeekBarView.java:529-536`) *and* re-clamps progress (`:229-234`), with `minThumbX()` (`:353`) pinning the thumb at the floor. There is no upstream path that draws the dimmed band while leaving the thumb below it. Skipping `setMinValueAllowed` entirely gives an honest thumb but re-opens the inert-step bug the floor was built to close — a thumb resting on a step the row cannot actually draw (`ComposerToolbarLayout.spacingIsUsable`, `ComposerToolbarLayout.java:442-446`). So the fork's answer is the footer disclosure (`spacingFooterText()`), not a custom thumb: the value is honestly saved and reported in words, and the slider keeps upstream's clamp untouched.

*(Established 2026-09-06, #composer-spacing.)*

## "The fork has no reusable pill/segmented tab control"

Disproven 2026-09-09 (#ghost-hold). A prior recon concluded no reusable
segmented/pill control existed and that one would have to be hand-rolled.
`org/telegram/ui/Components/FilledTabsView.java` is exactly that: a ~134-line
pill/segmented control with `setTabs(CharSequence...)` (`FilledTabsView.java:32`)
and an `onTabSelected` hook (`:54`), already used at `PeerColorActivity.java:1591`
and `PinnedReactionsActivity.java:192`. Reuse it rather than building a new one.

## Per-chat "already warned" state is not categorically forbidden for Ghost Mode features

The send-time Ghost warning (`#ghost-send-warning`) used to track "warned this
chat already this session" with a stateful per-account set. It was introduced
in `5f8e27ed0c` ("warn once per chat when a send while Ghost is on exposes
online status"), and its reset edge was corrected twice: `80a5c0c57f` ("detect
ghost session boundary from the live predicate, not one toggle path") and then
`b696068599` ("observe ghost state at both toggle write paths, not only at
send time"), which is the commit that gave `GhostModeActivity`/`NekoConfig`'s
settings-UI toggle write path its own call into the helper alongside the
send path's own observation — two call paths, on two different threads,
sharing one mutable set with no synchronization yet. That race was fixed next,
in `67042a6884` ("synchronize shared warned-dialogs state between send path
and settings writes"), which added a `LOCK` around both paths' access to the
shared fields — this did not remove the state, only make its two writers safe
to interleave. `797a510074` ("gate the warning on an active UI, not just Ghost
being on") is often mistaken for the removal too, but it only added a
`LaunchActivity.isActive` gate on top of the same still-present, still-locked
`warnedDialogsByAccount`/`wasGhostActive` state.

The state was actually removed later and for an unrelated reason, in
`b4664bfe11` ("re-hook ghost send warning at the tgnet dispatch chokepoint"):
that commit moved the hook from a generic send-request dispatcher (which also
carried non-send requests like `TL_messages_editMessage` and silently
consumed the once-per-chat slot on them) to
`ConnectionsManager#sendRequestInternal`, and paired that hook-point fix with
a separate product decision to warn on every exposing send instead of only
the first one per chat per session — a decision that made the per-chat state
moot outright, not a decision driven by the earlier (already-fixed) race. Its
commit message is explicit about this: "This also removes the once-per-chat
warning entirely, per product decision... That deletes every piece of state
the previous design needed a lock for... because there is no longer any
session-scoped decision to protect from a race" (note "no longer" — the race
itself was old news by then, already closed by `67042a6884`).

This is **not** a blanket rule against any per-chat Ghost state — the typing-time
reminder added under `#ghost-type-warning` keeps materially the same shape of
state (an account-keyed set of already-reminded dialogIds, reset lazily on a
Ghost off→on edge) and is fine, because the thing that made the old design's
*state* need a lock — a background-thread writer (the send path) racing a
UI-thread writer (the settings toggle) — doesn't apply here at all. Every read
and write happens on the UI thread: `ChatActivityEnterView`'s own `TextWatcher`
(`ChatActivityEnterView.java:7069` is the only call site of
`GhostTypingReminderHelper.onComposerTypingObserved`) is the sole entry point,
and the `AndroidUtilities.runOnUIThread` runnable it posts
(`GhostTypingReminderHelper.java:280-327`) is a second UI-thread access path,
not a background one. The settings screen never touches this set, and the send
path touches it only as a read-only UI-thread reader, described in the
paragraph below. If a future change makes this state reachable from anywhere
but those UI-thread paths, revisit this exemption rather than assuming it
still holds.

That revisit has since happened once, and the exemption survived it. A later
`#ghost-type-warning` change added a third reader: the send-time warning now
stays quiet in a chat the typing reminder already covered this Ghost session,
which it asks via `GhostTypingReminderHelper.wasRemindedThisGhostSession`. The
tempting place to put that question is the send hook itself, which is exactly
the cross-thread read that got the old design deleted --
`sendRequestInternal` runs on whichever thread dispatched the request, normally
`Utilities.stageQueue` but inline on the caller's own thread for
`sendRequestSync` (`ConnectionsManager.java:393-395`), and in neither case the
UI thread. It isn't there: only the destination dialog id is resolved on that
thread, where the outgoing request is in hand, and captured as a primitive; the
*send path's* access to the set happens only inside `GhostSendWarningHelper`'s
pre-existing `runOnUIThread` block. The composer's
own two paths are unchanged (the `TextWatcher` at
`ChatActivityEnterView.java:7069` and the UI runnable it posts), so the full
inventory is now three access paths, all on the UI thread. Only one of them
writes a dialog id into the set: the posted runnable. The `TextWatcher` path
reads membership and posts; the send helper's path is read-only and never
records anything, so it cannot consume a slot the user was never shown. The
rule the exemption actually rests on is unchanged: no background-thread
access, so still no lock.

That the send path does not record is a decision, not an oversight, and review
has proposed reversing it — recording there would make a forward or gallery
send into a not-yet-reminded chat start the quiet period, which reads as the
more consistent rule. It is refused for the same reason the reminder path is
allowed to record: what is observable is that a bulletin reached `show()`
against an eligible host, which is an eligibility test rather than proof the
user saw it. On the typing path the two coincide, because a keystroke in a
chat's own composer means that chat is on screen. On the send path they do not
— its trigger can arrive long after the user acted, e.g. a completing upload
dispatching its result while the user is elsewhere — so recording there would
let an unseen bulletin silence every later warning in that chat. The
consequence is that a chat only ever warned at send time keeps warning, and
`FEATURES.md` must say so rather than describing the quiet period as something
any warning starts.

Keeping the read on the UI thread has a consequence worth stating, because it
was raised in review and **accepted rather than fixed**: the send path reads
the set when its runnable runs, not when the request was dispatched, so the two
are not ordered against each other. A send dispatched at T0 hops to the stage
queue and only posts its UI runnable from there, while a first keystroke in the
same chat's composer at T0+ε posts its reminder runnable directly. The reminder
can therefore be recorded first and suppress a warning for a send that actually
predates it. Ordering them properly would mean the send path sampling the set
at dispatch time -- on a non-UI thread -- which is the exact cross-thread read
this whole entry exists to forbid, so the alternative is a lock and timestamps,
i.e. reviving the shape that was deleted. Not worth it here: the window is a
few milliseconds wide, it requires an untyped send immediately followed by a
first keystroke in the same chat, and in it the user is still shown a Ghost
exposure bulletin -- the typing reminder, whose message is materially the same
warning. The outcome is one bulletin instead of two, which is what this change
was for, not silence.

A later `#ghost-type-warning` change (2026-09-11) added a fourth condition
to `onComposerTypingObservedUnsafe`, ahead of every path above: it reads
`NekoConfig.holdMessagesWhileGhost.Bool()` fresh, both at the synchronous
entry and again inside the posted runnable, and returns without touching
`remindedSetForEpoch` at all when Hold Messages is on
(`GhostTypingReminderHelper.java:237-254,284-293`). This is a plain early
return, not a new state machine: it sits before every read/write this section
analyzes, so none of the epoch/account/thread reasoning above changes because
of it -- a chat this returns for simply never enters the set, exactly as if
Ghost Mode itself were off for that keystroke. The one property worth stating
because it does not fall out of anything above: whether a chat's reminder was
skipped for this reason is not itself remembered anywhere, so a keystroke in
that chat after Hold Messages is later turned off in the same Ghost session is
indistinguishable from that chat's first qualifying keystroke of the session,
and reminds normally. The suppression this section spent so much analysis on
protecting (the send-time warning deferring to `wasRemindedThisGhostSession`)
is unaffected by Hold's own state at send time, deliberately: see
`GhostSendWarningHelper.java`'s class javadoc for why a chat already reminded
while Hold was off stays quiet for the rest of that session even after Hold is
later turned on.

Unlike the deleted send-time state, this feature needed its own transition
counter to know when a Ghost session actually restarted, and that counter
went through four revisions to reach its current shape, each one
correcting a different mistake in the last:

1. The first cut bumped a `ghostSessionEpoch` counter, owned by `NekoConfig`,
   from inside `NekoConfig#setGhostMode`, i.e. only the master Ghost Mode
   switch's write path. That missed any false→true transition produced by
   flipping the individual per-signal toggle rows or their locks in
   `GhostModeActivity` one at a time until the combined predicate happened to
   read true again, since none of those paths call `setGhostMode`.
2. The second cut moved the bump to the *read* side instead --
   `NekoConfig#isGhostModeActive()` itself tracked the last value it returned
   and bumped the epoch on every observed false→true edge, regardless of
   which write path caused it, so it self-healed from any caller. That
   self-healing came at a cost review caught in two parts: first, a genuine
   cross-thread bug (`isGhostModeActive()` is called synchronously from
   `GhostSendWarningHelper` on `Utilities.stageQueue`, not just the UI thread,
   so the new read-modify-write needed a lock to be safe), and then, more
   fundamentally, a contract break underneath that same fix -- `NekoConfig`'s
   `isGhostModeActive()` is a shared predicate the Ghost Hold PR
   (`#347`) also calls, from several places, on the assumption that it is
   pure and side-effect-free and therefore safe to call from anywhere,
   including background threads. Silently turning it into stateful,
   synchronized read-modify-write to serve only this feature's own reset
   bookkeeping was the wrong trade regardless of whether the immediate race
   got fixed -- a shared function's threading contract isn't this feature's
   to change out from under a sibling that depends on it.
3. Revision 3 reverted `isGhostModeActive()`
   (`NekoConfig.java:307-320`) to exactly its pre-feature form: the original
   loop over `ghostToggleItems`, nothing else, no state, safe to call from
   any thread. All of this feature's own transition-tracking moved out of
   `NekoConfig` entirely and into `GhostTypingReminderHelper`: a private
   `ghostSessionEpoch` field bumped by an `onGhostModeMasterSwitchActivated()`
   method called from `NekoConfig#setGhostMode` on a false→true transition
   captured before the loop mutates anything -- still just *reading* the
   now-pure predicate, never writing through it. That kept the purity win but
   reintroduced revision 1's master-switch-only scope limitation, which is what
   revision 4 had to go back and fix.
4. The current design keeps revision 3's placement and purity exactly, and
   changes only *who decides an edge happened*. `onGhostSignalsChanged()`
   (`GhostTypingReminderHelper.java:116-122`) is an **observer**, not a
   notification: it reads the pure predicate itself, compares against the last
   value it saw (`lastObservedGhostActive`,
   `GhostTypingReminderHelper.java:94`, deliberately a nullable `Boolean` so
   the first observation in a process is not mistaken for an activating edge),
   and bumps the epoch only on a real false→true. Callers therefore don't have
   to know whether they caused a transition, which is the whole point --
   the predicate has no single writer. It is called from
   `NekoConfig#setGhostMode` (`NekoConfig.java:324-349`), from
   `GhostModeActivity#updateGhostViews` (`GhostModeActivity.java:153`, the one
   point all five individual signal rows already route through after toggling),
   and from `GhostModeActivity#onItemLongClick`'s lock branch
   (`GhostModeActivity.java:255`, since a locked signal is skipped by the
   predicate and so locking one can flip it on its own). An extra call that
   changed nothing is a no-op, so density is free.

The helper's own state went through a separate correction of its own. An
earlier cut paired the epoch with a raw `SparseArray<HashSet<Long>>` plus a
separate `lastObservedGhostSessionEpoch` int, cleared with an explicit
`clear()` call when the two didn't match. That had a gap: if Ghost Mode cycled
off→on a *second* time with no composer callback in between to observe the
first reset, the posted `runOnUIThread` runnable could still read back a set
that belonged to an already-ended session, because nothing forced a re-check
at the point of use — a raw `get()` doesn't know it's stale. The current shape
(`GhostTypingReminderHelper.java`) closes that by giving every stored set its
own epoch and only ever reaching it through one accessor: `PerAccountState`
pairs a `HashSet<Long>` with the epoch it was created for (and with the account
slot's logged-in user id, so a slot that comes back holding a *different* user
invalidates it the same way a stale epoch does — a logout and fresh login as
the same user does not, and does not need to), held in `stateByAccount`, and
`remindedSetForEpoch(account, epoch)` is the only way anything reads or
creates one — it replaces a stale entry with a fresh one for the requested
epoch on the spot. All three call sites -- the synchronous check in
`onComposerTypingObservedUnsafe`, the posted runnable, and
`wasRemindedThisGhostSession` -- go through this same accessor with a
freshly-read `ghostSessionEpoch` each time, rather than either caching a set
reference or branching on "did the epoch move" — there is no separate reset
step for a future change to forget to call, and no window where a set can be
read before it's known to be current for its epoch.

The user-id half of that needs one more piece, because the accessor answers for
whoever is logged into the slot *at the moment it is called*, and two of those
three call sites run a main-loop turn after the event they describe. So both
also carry the user id they started with — captured before the
`AndroidUtilities.runOnUIThread` hop in each case — and bail if the slot has
changed hands since. Without it, binding the set to a user id is defeated from
either end: the reminder's posted runnable would record a chat into the *new*
user's set, and the send-time query would let the new user's set answer for a
send that was not theirs. Both then cost a warning in a chat nobody was
reminded about, since two users in one slot can share a dialog id through any
group both are in. On the send side that snapshot is taken before the
destination is resolved, not after, and the resolver is handed it rather than
re-reading — otherwise the Saved Messages mapping and the check validating it
could observe the slot either side of the same logout, and agree.

**State the boundary of that snapshot precisely, because it is not the
request's originating identity.** It is sampled where the hook is, in
`sendRequestInternal`, and the usual `ConnectionsManager#sendRequest` posts
that to `Utilities.stageQueue` first (`ConnectionsManager.java:400-402`, verified
2026-09-11). So it covers everything from the stage-queue turn onward, not the
gap between the user's send action and that turn. Closing that gap would mean
capturing an identity in `sendRequest` and threading it through
`sendRequestInternal`'s signature — a permanent widening of the fork's
footprint in one of the hottest methods upstream owns, which is not worth it
here, because the ordering makes the gap fail open rather than suppress:

- `Utilities.stageQueue` is a single FIFO `DispatchQueue`
  (`Utilities.java:40`), which is one `Thread` with one `Handler`
  (`DispatchQueue.java:19`, `:133`) whose ordinary `postRunnable` is a plain
  `handler.post` (`DispatchQueue.java:103`) — and `sendRequest` uses that, not
  the `postAtFrontOfQueue` variant beside it (`:93`). So a send posted before a
  logout runs before anything the subsequent login posts to it. The queued send
  cannot be overtaken by the new user's session coming up. (Verified
  2026-09-11.)
- A logout nulls `currentUser` via `UserConfig.clearConfig()`, so a send that
  did somehow run after it samples `getClientUserId()` as 0 — which is
  `DIALOG_ID_UNRESOLVED`, and which then cannot equal the new user's id at the
  UI check either. Both the resolution and the validation fail open.

So the suppression cannot be handed the wrong user's set this way; it can only
lose the suppression and warn. Anything that later moves this hook earlier, or
makes `getClientUserId()` return a stale non-zero value across a logout, breaks
that reasoning and puts the prescribed capture-before-the-hop fix back on the
table.

**The residual gap this entry used to record as accepted is now closed, and
the thing that forced the issue is worth recording.** Revisions 1 and 3 both
observed exactly one path back into an active Ghost session: the master Ghost
Mode toggle. An off→on transition produced by flipping individual per-signal
toggle rows or their locks in `GhostModeActivity`, one at a time, until the
combined predicate happened to read true again, did **not** advance the epoch,
so whichever chats were already reminded stayed suppressed into what the user
experienced as a new session. That was tolerable only while the worst case was
a missed *reminder*: `GhostSendWarningHelper` checked
`NekoConfig.isGhostModeActive()` fresh at send time
(`GhostSendWarningHelper.java:231-233`) and carried no per-chat state, so a send
was never left unsignaled.

Making the send-time warning defer to the reminder
(`GhostSendWarningHelper.java:296-299` asking
`GhostTypingReminderHelper.wasRemindedThisGhostSession`) destroyed that
independence: the two now share one piece of state, so a reset the epoch
missed cost not just the early nudge but the send-time bulletin too, and a
media send or forward into that chat would have gone out with no signal at
all. A stale-state bug that costs a redundant notification and one that costs
the only warning are not the same bug, and the accepted trade did not survive
the severity change. Hence revision 4. The lesson generalizes past this
feature: **when something starts consuming an existing piece of state to
*suppress* a safety signal, the state's known-imprecise cases have to be
re-costed at the new severity, not inherited along with the state.**

Two constraints recorded in earlier rounds were overridden to do it, both
deliberately. Instrumenting `GhostModeActivity` was avoided in round 1 to stay
textually conflict-free with the then-unmerged `#ghost-hold` work that touches the
same file; correctness won, at the cost of a small merge resolution in that file
when Ghost Hold landed. And [issue #339](https://github.com/dazewell/Dazegram/issues/339)
proposed waiting for Ghost Hold (which merged as `#347`) and then reusing its
`GhostHoldController#onGhostStateMaybeChanged`. That is the same
derived-edge-detector shape arrived at here independently, which is good
evidence it is the right one -- but it could not be waited for. Ghost Hold has
now merged, and the two detectors deliberately coexist rather than being
collapsed: `NekoConfig#setGhostMode` (`NekoConfig.java:347-348`) and
`GhostModeActivity#updateGhostViews` (`GhostModeActivity.java:147-153`) each call
both `GhostHoldController.onGhostStateMaybeChanged()` and
`GhostTypingReminderHelper.onGhostSignalsChanged()` off the same predicate edge,
with disjoint consumers. Collapsing them into one shared detector is deferred to
#339: unifying them would restructure Ghost Hold's freshly-shipped,
device-verified flush trigger for no behaviour change, which reliability-first
does not justify. Reviving a stateful `isGhostModeActive()` remains ruled out
for the reason in revision 2.

*(Established 2026-09-10, #ghost-type-warning. Revision 4 and the
severity-re-costing lesson added 2026-09-10, same slug. Coexistence of the two
edge detectors confirmed at merge 2026-09-11, #ghost-type-warning; collapse
tracked by #339.)*

## "GhostHoldStore.encode() truncates the attach path that decode() reads back"

Disproven by reading the serialization path on 2026-09-11. The automated
reviewer flagged `GhostHoldStore.decode()` calling `message.readAttachPath(nbb,
selfId)` (`GhostHoldStore.java:610`) while `encode()`
(`GhostHoldStore.java:625-640`) "does not append an attach-path payload despite
the docstring saying it does," suspecting a decode misparse. It does append it,
implicitly. `encode()` allocates `message.getObjectSize()` bytes and calls
`message.serializeToStream(data)` -- byte-for-byte the stock store pattern at
`MessagesStorage.java:11431-11432`. Every `TL_message*.serializeToStream(...)`
ends with `writeAttachPath(stream)` (e.g. `TLRPC.java:57927`), and
`getObjectSize()` computes its size by running that same `serializeToStream`
into a counting buffer (`TLObject.java:86-91`), so the attach path is inside
the blob and the allocation already accounts for it. `decode()`'s
`readAttachPath` is additionally guarded by `if (stream.remaining() > 0)`
(`TLRPC.java:57661`), so even a blob with no trailing bytes is a safe no-op
rather than a misparse. The docstring is accurate; there is no asymmetry versus
stock. No code change was made.

*(Established 2026-09-11, #ghost-hold.)*

## `verCode`'s low digit does not encode a distribution channel in this fork

Raised in review on PR #351 (the 1262 -> 1263 bump): that upstream treats the
last digit of `versionCode` as a distribution marker, so `...3` is "not a valid
next value" and every `% 10` / `/ 10` consumer would have to be updated with it.
The cited code is real, but the conclusion does not hold here, on three counts.

**There is no invalid low digit.** All four switch sites give `default:` and
`case 9:` the same branch body (`ApplicationLoader.java:357-365`,
`AndroidUtilities.java:6722-6730`, `LoginActivity.java:4180-4198`,
`LoginActivity.java:10273-10291`). Any low digit other than `1` or `2` takes the
identical path, so `3` is handled exactly as `9` is — not unhandled.

**The lineage abandoned the convention long ago.** Release tags in this repo:
1231 (1), 1233 (3), 1240 (0), 1244 (4), 1246 (6), 1250 (0), 1254 (4), 1256 (6),
1258 (8), 1260 (0). Tag `1233` already shipped the exact low digit flagged. The
1/2/9 scheme is a Telegram-official-distribution artifact; 1261 and 1262 landing
on "store bundled" was the anomaly, not the baseline.

**The consumers are diagnostic strings, not behaviour.** There are four, in two
classes. The `ApplicationLoader` site is inside `if (BuildVars.LOGS_ENABLED)` and
only composes a `FileLog.d` line. `AndroidUtilities.getBuildVersionInfo()`
(`AndroidUtilities.java:6712`) is called only from the debug-report builder
(`LaunchActivity.java:7881,7887,7918`). The two `LoginActivity` sites
(`LoginActivity.java:4180-4198`, `LoginActivity.java:10273-10291`) append an
`App version: <version> <versionType>` line to the support-report bodies for the
no-OTP and billing-issue flows; their default branch is richer than the other
two, resolving to `direct`/`beta`/`huawei`/`universal` rather than just the first
and last. Decisively, `ApplicationLoader.isStandaloneBuild()`
(`ApplicationLoader.java:160`) does **not** read `versionCode` — the low digit
selects a label appended to a string and nothing else. No install-identity,
update or billing *behaviour* path consumes it; the billing-issue site reports a
label, it does not gate anything. The fork's own about screen uses
`AndroidUtil.getVersionText()` (`AndroidUtil.java:194`), which prints
`BuildConfig.VERSION_CODE` whole rather than divided by 10.

Net effect of a low-digit change: the debug log line, the copy-debug-info string,
and the `App version:` line in a support report read `direct`/`universal` instead
of `store`. Do not edit the `% 10` / `/ 10` consumers to "fix" this — they are
upstream base files and the change would buy a diagnostic string at the cost of
merge surface.

*(Established 2026-09-11, #release.)*

## "The forward-selection order inversion at ChatActivity.java:3918 reverses held rows too"

Disproven. The selected-id sort before forwarding orders ids *ascending*
(`ChatActivity.java:3918`), which for decrementing negative local ids is
newest-first — so it looks like it would forward several held rows in reverse
hold order, the same symptom as the Scheduled-list ordering bug. It cannot,
because **held rows never enter the selection model at all.** The guard sits at
the entrance, not on the forward path: `addToSelectedMessages`, the reply/quote
preview loop, and `canSelect` each refuse a held row, and a held row's only
action is delete through the single-row cancel menu, which does not go through
the selection model (the `isHeld` guards sit at the selection entrances —
`canSelect` at `:1985` (guard `:1992`), the reply/quote preview loop at `:12379`
(the `continue` before its selection-map write at `:12392`), and
`addToSelectedMessages` at `:20906` (guard `:20914`) — and `naxExcludeHeldFromSend`
(`:37238`, its own guard at `:37245`) strips any held row from the send-assembly
paths as a second line of defence, called from `:4384`, `:16865`, `:37283` and
`:49115`; `resolveRescheduleItems` (`:37912`) likewise skips held rows at `:37923`).
With no held row ever selected, `:3918` never sees
one, so the `#ghost-hold` scheduled-order fix deliberately leaves it untouched.

The next reader will look at `:3918`, see the same apparent inversion, and be
tempted to "fix" it for held rows. Don't — the path is unreachable for them.

*(Established 2026-09-11, #ghost-hold. Citations re-verified 2026-09-12.)*