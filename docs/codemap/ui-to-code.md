# UI → code map

"When the user taps X, the code that runs is Y." Re-verify the citation
before relying on it — see the README.

## BottomBuilder section cards are opt-in and isolated to Early Send

`BottomBuilder` now has a fourth constructor arg `sections` defaulting `false`,
so existing callers keep the old layout path by default
(`BottomBuilder.kt:32-43`). In default mode, root/content behavior stays the
same (`LinearLayout` root under a plain `ScrollView`); in sections mode only,
the builder swaps to `SectionsLinearLayout` + `SectionsScrollView`
(`BottomBuilder.kt:45-67`).

Section-mode-only chrome is also gated there: the button strip is tagged
`RecyclerListView.TAG_NOT_SECTION`, the strip background is gray, and
`create()` applies gray sheet/nav-bar background (`BottomBuilder.kt:70-82`,
`:300-306`). In sections mode, `addTitle(...)` wraps the builder-owned
`HeaderCell` in a full-width `FrameLayout`; the inner `HeaderCell` is tagged
out while the wrapper remains the section member (`BottomBuilder.kt:107-122`).
`EventScheduleHelper` is the only caller opting in:
`new BottomBuilder(context, true, Theme.getColor(Theme.key_windowBackgroundGray), true)`
(`EventScheduleHelper.java:656`).

The wrapper's own bottom padding is what controls the card's bottom inset, never a
margin: `SectionsScrollView.drawSectionBackground` only reads a **margin** on the
non-content-view side of a section run (`SectionsScrollView.java:146-148`,
`:150-155`), so a margin on the wrapper's own trailing edge (`bottomMargin`,
`BottomBuilder.kt:120-122`) never reaches the rounded rect — only the wrapper's
*measured height*, padding included, does. That's why the 15dp bottom inset added
under `EventScheduleArmed`'s subtitle (`BottomBuilder.kt:118`) is `headerContainer`
padding, not a `HeaderCell` margin: `HeaderCell.setBottomMargin(...)` writes
`bottomMargin` onto **both** the title and subtitle `LayoutParams`
(`HeaderCell.java:137-142`), stacking on top of the subtitle's existing 4dp
`topMargin` (`HeaderCell.java:101`) and moving the title→subtitle gap that was
never meant to change.

*(Updated 2026-09-08.)*

## Send on event card membership and collapse behavior

The *Send early on event* sheet now uses a local `DisclosureHeaderCell`
subclass of `TextSettingsCell` for both collapsible group headers, with summary
value text and explicit accessibility state text
(`EventScheduleHelper.java:274-346`). The disclosure cue is an inline
`ColoredImageSpan(R.drawable.arrow_more)` appended to the title text and rotated
with the same 340ms `EASE_OUT_QUINT` curve when expanded/collapsed, so the cue
measures with the title in the stock `TextSettingsCell` layout path.
These disclosure rows are section
members (not tagged out), so each group header sits inside its card with its
controls directly beneath it.

Card ownership now has permanent boundaries that do not depend on descendant
visibility: an intro/type boundary spacer immediately after `addTitle(...)`,
the existing permanent type/text spacer, and a permanent text/delay spacer
after `patternInfo` (`EventScheduleHelper.java:658-661`, `:707-710`, `:748-751`).
With those boundaries, composition stays stable as four primary cards: intro title
card, type card (header + five type rows), text card (header + `patternArea` +
regex row), and delay card (`EventScheduleHelper.java:657`, `:668-702`,
`:712-740`, `:1031-1033`). If trigger state is already enabled, the optional
remove action still appears after those four cards with its own tagged gray
spacer (`EventScheduleHelper.java:1035-1044`).

`patternInfo` remains a `TextInfoPrivacyCell` after regex, outside card
grouping by `SectionsScrollView.isSectionView(...)` class exclusion
(`EventScheduleHelper.java:745-747`; `SectionsScrollView.java:36-41`). Text
collapse still toggles `patternArea`, regex, and `patternInfo` visibility, but
the permanent boundaries keep text and delay as separate card runs even when
the text descendants are `GONE` (`EventScheduleHelper.java:893-901`;
`SectionsScrollView.java:91-98`).

Divider behavior is now explicit: header dividers draw only while expanded,
type rows clear the last divider in the group, and regex is always the text
card's last row with no bottom divider (`EventScheduleHelper.java:885-890`).
Delay UI remains wrapped in a full-width `FrameLayout` so sections treat it as
one card, and the remove separator now uses literal `12` dp units (no double-dp)
(`EventScheduleHelper.java:1031-1039`).

Hidden-group validation behavior remains in the same code path: Done expands a
collapsed text group before showing row-level invalid-regex feedback, and
no-condition failure expands actionable groups before the existing toast
(`EventScheduleHelper.java:1067-1108`).

*(Updated 2026-09-07.)*

## Covered notification silent-tier decision in mixed rebuilds

Covered-child silent/alert selection is now explicitly split by scope.
Preflight captures a rebuild-wide suppression bit
`naxRebuildSuppressed = !notifyAboutLast || isRecordingAudio()` and builds an
immutable per-covered-dialog map `naxCoverSuppressed`, keyed from the covered
snapshot (`naxMessagesByDialogs`) using the extracted read-only helper
`naxCoveredDialogSuppressed(...)` (notify override/global-enabled, message
silence, per-chat `sound_enabled_`) (`NotificationsController.java:4193-4202`,
`:6001-6029`).

`showExtraNotifications(...)` now receives both values and derives child
`coverSilent` as:
`naxRebuildSuppressed || naxDialogSuppressed == null || naxDialogSuppressed || (dialogId == lastDialogId && isSilent)`,
then passes that into `NotificationCoverController.postChild(...)`
(`NotificationsController.java:4887`, `:4962`, `:5117-5119`). Inside
`postChild(...)`, growth is no longer derived from capped token snapshots.
Authoritative baseline now lives in per-dialog exact-membership prefs
(`KEY_ACTIVE_CHILD_MEMBERS`), while token snapshots stay capped interaction
payloads only (`NotificationCoverController.java:65`, `:739-776`, `:972-1038`).

Ordering is now split deliberately: under `COVER_STATE_LOCK`, child posting
reads prior exact membership (resolving stored ids through current alias map),
computes growth/migration/over-capacity, and rotates only tap/dismiss token
records first (`NotificationCoverController.java:767-776`, `:1085-1169`); after
`notify(...)` succeeds, it re-locks and writes the new membership baseline only
when the active tap pointer still equals this post's token (CAS guard),
preventing stale rewrites after concurrent interaction or rebuild
(`NotificationCoverController.java:803-811`).

Over-capacity represented sets (`displayCount > SUPPRESSION_LIMIT`) are marked
explicitly in `buildPostPlan` and always forced silent; the stored baseline is
an explicit sentinel (`over_capacity`) rather than a truncated id list
(`NotificationCoverController.java:561-562`, `:771`, `:1095`, `:1125-1134`).
Migration is also explicit: missing membership + existing active child token
forces silent for that post and seeds baseline only after successful notify/CAS
(`NotificationCoverController.java:767-770`, `:778`, `:803-811`).

Cleanup ownership is centralized: membership state is cleared only by
`clearDialogInteractionState(...)`, and stale/orphan membership keys are pulled
into reconcile candidate scanning through the membership prefix
(`NotificationCoverController.java:1278-1282`, `:1341-1343`).

*(Updated 2026-09-07.)*

## Chat privacy overflow row owns both per-chat privacy controls

The in-chat overflow menu now has one `Chat privacy` row (`nkheaderbtn_chat_privacy`)
that opens `ChatPrivacySheet.show(...)` (`org/telegram/ui/ChatActivity.java:498`,
`:5168`, `:48085-48086`).

Inside that sheet, `Hide last message` toggles
`HideLastMessageController.setHidden(...)`, and the `Placeholder text` value row
opens `HideLastMessageDialog.showPlaceholderEditor(...)` for Save/Cancel editing
(`com/radolyn/ayugram/chatprivacy/ChatPrivacySheet.java:173`, `:181`;
`com/radolyn/ayugram/hidelastmessage/HideLastMessageDialog.java:113-172`).

`Require password` state is read from the persisted lock flag via
`ChatLockController.isFlagged(...)` (not `isLocked(...)`), so a stored flag is
still shown when the global app passcode is absent
(`com/radolyn/ayugram/chatlock/ChatLockController.java:70-80`;
`com/radolyn/ayugram/chatprivacy/ChatPrivacySheet.java:123-151`, `:186-194`).
When turned on with a passcode present, the sheet keeps the existing one-way
coupling: it auto-enables hide only when hide was off, preserving a custom
placeholder, and shows the existing enabled bulletin (`ChatPrivacySheet.java:197-204`).

*(Updated 2026-09-07.)*

## Chat privacy card membership and stock bulletin placement

`ChatPrivacySheet` now builds content on `SectionsLinearLayout` and wraps it
in `SectionsScrollView`, with gray sheet/nav-bar backgrounds applied after
`builder.create()` and before `showDialog()` (`ChatPrivacySheet.java:53`,
:268-297`). Card membership is split by tags: title, notifications header,
and `How covers work` disclosure are marked `TAG_NOT_SECTION`; card 1 is
hide/placeholder/require-password and card 2 is disguise/cover/preview
(`ChatPrivacySheet.java:61`, `:79`, `:96-99`).

The sections scroll migration now explicitly attaches `content` as
`MATCH_PARENT x WRAP_CONTENT` under the `SectionsScrollView`, matching the
builder sections path (`ChatPrivacySheet.java:271`).

The notifications behavior wiring is unchanged in ownership: switch -> 
`setEnabled(...)` + notifications rebuild, cover picker -> `setPersona(...)` +
rebuild, preview -> `postPreview(...)` + bulletin feedback
(`ChatPrivacySheet.java:211-242`;
`tw/nekomimi/nekogram/helpers/PopupHelper.java:32-54`).

Require password / Disguise / Preview bulletins now call
`BulletinFactory.of(sheetRef[0].container, rp).createSimpleBulletin(...).show()`
directly under the existing `sheetRef[0] != null` guards, with no custom
delegate, offset host, or row-anchor wrapper (`ChatPrivacySheet.java:194-203`,
`:212-220`, `:236-243`).

Cover config is stored in the account's notifications `SharedPreferences`
(`MessagesController.getNotificationsSettings(account)`), keyed
`nax_cover_v1_enabled_<dialogId>` / `nax_cover_v1_persona_<dialogId>`, with lazy
generic channels under `nax_cover_v1_channel_<personaId>` /
`nax_cover_v1_summary_channel`
(`com/radolyn/ayugram/chatprivacy/NotificationCoverController.java:57-68`,
`:201-229`, `:654-671`).

*(Updated 2026-09-07.)*

## Tokenized broadcast interaction path for covered notifications

All cover interactions now use immutable **broadcast** PendingIntents into the
non-exported `NotificationDismissReceiver`: child tap/dismiss, summary tap/dismiss,
and preview tap all resolve via the same tokenized receiver path in
`NotificationCoverController.handleInteraction(...)`.

Android 12+ blocks a notification broadcast receiver from launching the chat
activity directly. Supporting cover-tap open-chat would therefore require a
dedicated transparent activity bridge; by product decision, that complexity was
dropped and covers are Hollow-only, so broadcast is the only interaction
transport now needed
(`NotificationCoverController.java:686-725`, `:745-793`, `:796-828`, `:860-955`, `:995-1068`;
`org/telegram/messenger/NotificationDismissReceiver.java:27-33`).

*(Established 2026-09-03.)*

## Covered-chat clear hooks and accepted open-attempt behavior

`ChatActivity` routes cover-clear attempts through
`clearCoveredNotificationsIfVisible()`, called from the chat lifecycle path
that also sets `openedDialogId`, from `onBecomeFullyVisible`, and from the
post-chat-lock-unlock callback
(`org/telegram/ui/ChatActivity.java:3755-3759`, `:3776-3784`, `:29244`,
`:32179`). In that helper, current code checks `MODE_DEFAULT`, nonzero
`dialog_id`, `chatLockPasscodeView == null`, and app-passcode flags before
calling `NotificationsController.suppressVisibleCoveredDialog(dialog_id)`
(`ChatActivity.java:3776-3784`).

`suppressVisibleCoveredDialog(...)` posts onto `notificationsQueue`; the cover
controller suppression path runs there and then triggers notification rebuild
(`org/telegram/messenger/NotificationsController.java:3313-3318`;
`com/radolyn/ayugram/chatprivacy/NotificationCoverController.java:560-593`).

Accepted on-device behavior: attempting to open a protected covered chat may
consume the current cover before successful visibility (including cancelled or
failed unlock). This map documents the fork-owned clear hooks above; it does
not attribute or alter upstream read-state behavior, which is tracked
separately in issue #286.

*(Established 2026-09-03.)*

## Selection bar left button ("NoQuote")

The button at the bottom-left of the message-selection action bar is the
**configurable left action button**, not an overflow menu item. It defaults
to the NoQuote forward action, present out of the box with no setting turned
on:

- Action constants: `ChatsHelper.LEFT_BUTTON_*`
  (`tw/nekomimi/nekogram/helpers/ChatsHelper.java:42-47`).
- Which action is active: `NaConfig.leftBottomButton`
  (`NaConfig.kt:1314-1319`, `LeftBottomButtonAction`), **default `0` =
  `LEFT_BUTTON_NOQUOTE`**.
- Label: `ChatsHelper.getLeftButtonText` (`ChatsHelper.java:91-99`) — the
  default case returns `NoQuoteForwardShort`.
- Click handling: `ChatsHelper.makeReplyButtonClick`
  (`ChatsHelper.java:134-167`). The `LEFT_BUTTON_NOQUOTE` case sets
  `ChatActivity.noForwardQuote = true` and calls `openForward(false)`
  (`ChatsHelper.java:157-166`).

**This is a different door from `nkbtn_forward_noquote`**, the item in the
selection *overflow* menu, gated by `NaConfig.showNoQuoteForward`
(`NaConfig.kt:171-176`, config key `NoQuoteForward`, **default `false`**).
Conflating the always-present left button with this off-by-default menu item
produced two wrong conclusions in one session before this was written down.

`LEFT_BUTTON_DIRECT_SHARE` is the only left-button action that reaches
`ShareAlert`, via `createShareAlertSelected`
(`ChatsHelper.java:142-144`). Forwarding through the NoQuote button does
**not** go through `ShareAlert`.

*(Established 2026-09-02.)*

## Single tap on a scheduled message

A single tap on a row in the scheduled-messages list opens the **message
context menu**, not selection mode. `ChatActivity`'s item click listener only
routes to row-selection (`processRowSelect`) when the action bar is already
showing selection mode; otherwise it falls through to `createMenu(view,
true, false, x, y, false)`
(`org/telegram/ui/ChatActivity.java:2053-2077`).

`createMenu` clears any previously-checked selection before building the menu
— it resets `selectedObject`/`selectedObjectGroup`/`forwardingMessage` and
empties `selectedMessagesIds`/`selectedMessagesCanCopyIds`/
`selectedMessagesCanStarIds` for both message-list slots
(`ChatActivity.java:33060-33070`). A stale multi-selection from before the tap
cannot leak into the single-message menu that opens.

The "Reschedule" item in that per-message menu is
`ChatActivity.OPTION_EDIT_SCHEDULE_TIME` (`ChatActivity.java:1403`, handled at
`:36713`), string `MessageScheduleEditTime`. It is a **different feature**
from the toolbar's bulk reschedule button below.

*(Established 2026-09-02.)*

## Long-press Send opens the schedule sheet via `ChatActivityEnterView`, not the forward picker

Long-pressing the message input bar's Send button in a chat opens the schedule
sheet through `ChatActivityEnterView.onSendLongClick`
(`ChatActivityEnterView.java:5564`), whose "Schedule Message" popup item calls
`AlertsCreator.createScheduleDatePickerDialog`
(`ChatActivityEnterView.java:5619`). That call enters the 4-arg overload at
`AlertsCreator.java:4410` and funnels through five more delegating overloads —
`:4430` → `:4434` → `:4438` → `:4456` → `:4474` — into the terminal
implementation at `:4480`, where every schedule sheet is actually built.

**This is a different door from long-pressing Send in the forward chat-picker
(`DialogsActivity`).** The picker has its own long-press-Send handler,
`DialogsActivity.onSendLongClick` (`DialogsActivity.java:12224`), whose own
"Schedule Message" item (`:12291-12324`) calls the *same* `AlertsCreator.java:4410`
entry overload directly from the picker's own `writeButton`. The two are easy to
conflate — both are "long-press Send, choose Schedule" from the user's point of
view — but they are wired to different widgets, and only one of them fires for
a given gesture.

**A forward to a single chosen chat is staged into that chat's own input bar,
not sent from the picker.** `ChatActivity.openForward`
(`ChatActivity.java:13645-13730`) presents `DialogsActivity` as a
`DIALOGS_TYPE_FORWARD` picker; selecting a single destination chat that isn't
already open calls back into `ChatActivity.didSelectDialogs`
(`ChatActivity.java:36966`), which — for the plain single-chat, no-comment,
not-scheduled case — opens a new `ChatActivity` for that dialog and calls
`showFieldPanelForForward(true, fmessages)` on it
(`ChatActivity.java:37112-37143`) instead of sending immediately. That queues
the forward into the new chat's own field panel, so the Send button the user
then long-presses belongs to the target chat's `ChatActivityEnterView`, not the
picker's `writeButton`. This is why the picker's own schedule path can look
correct in review — it compiles, it is wired to a real menu item — and still
never execute for this gesture: the picker has already closed and handed off
before the user reaches the button it owns.

**Measured, not inferred.** Instrumented build `3a55877cb1` (confirmed
installed as `org.telegram.messenger.beta`, `versionName=12.10.1-3a55877`,
with the diagnostic literals verified present in the pulled `base.apk`'s DEX)
was exercised by selecting messages, using the fork's left NoQuote button,
choosing a target chat, and long-pressing Send → Schedule. The only captured
`NAX_SPREAD_DIAG` output was a `Throwable` at the
`createScheduleDatePickerDialog` chokepoint whose top frames were
`ChatActivityEnterView.lambda$onSendLongClick$63` funnelling into
`AlertsCreator.createScheduleDatePickerDialog`. No forward-picker presentation
logged during that same run either, consistent with the picker path not being
the one exercised for this gesture.

**Why it matters:** a spread-interval feature was built and reviewed against
the picker's schedule path (`DialogsActivity`), passed two architect rounds and
three independent final-state reviews, and did not work on device — because
none of those reviews could establish which code path a real long-press-Send
gesture actually takes on this device. That requires a stack trace from an
installed build, not a reading of the diff.

*(Established 2026-09-02, PR #270.)*

## Forward picker (`DIALOGS_TYPE_FORWARD`) has six presentation sites

`ChatActivity` opens the forward chat-picker (`DialogsActivity` with
`dialogsType == DIALOGS_TYPE_FORWARD`) from **six** places, each building its
own argument `Bundle`:

- `:4013` — quote-reply picker (single message).
- `:5807` — reply-to-author quote picker (single message).
- `:12327` — `selectAnotherChat` (`:12300`), the forward **preview's** "select
  another chat". Multi-message; populates `selectedMessagesIds[0]` (`:12322`)
  and syncs `noForwardQuote = messagePreviewParams.hideForwardSendersName`
  (`:12306`). **This is the route reached via "Hide sender's name"** — that
  toggle is a preview control, so stock Forward + hide-sender lands here, not in
  `openForward`.
- `:13728` — `openForward` (`:13651`), the selection bar's Forward / left
  NoQuote button. Multi-message.
- `:35941` — context-menu single-message forward (`OPTION_FORWARD`); sets
  `forwardingMessage`.
- `:36267` — context-menu reply-to-author (`OPTION_REPLY`).

The `#repost-spread` spread-interval gate needs the forward slot count. It was
first threaded as a Bundle int written by **only `openForward`**, so the other
five presentations — `selectAnotherChat` included — reached the gate with the
count defaulting to 0 and the interval row never appeared. The durable lesson:
**adding a value to one presentation of a shared screen has to enumerate the
others.** It is now computed at gate time from the delegate's live selection
(`DialogsActivityDelegate.getForwardSpreadSlotCount`, overridden in
`ChatActivity`) using the same selection logic the dispatch forwards, so no
presentation can reach the gate with a stale count.

*(Established 2026-09-02, PR #270.)*

## Channel post share arrow: two different code paths for "quick share sheet"

A single tap on the share arrow under a channel post opens
`ChatMessageCell.Delegate.didPressSideButton` (`ChatActivity.java:42733`), which
constructs `new ShareAlert(...)` (`ChatActivity.java:42773`) with `fullScreen`
and `forCall` both `false`. This is the bottom sheet with a "Send to..." search
field, a 4-column avatar grid, and a COPY LINK footer.

A **long-press-and-drag** on the same arrow is a completely different widget:
`didQuickShareStart` (`ChatActivity.java:42652`) opens
`QuickShareSelectorOverlayLayout` (`org/telegram/ui/Components/quickforward/`),
a hand-drawn popup with its own bespoke hit-testing. It shares no code with
`ShareAlert`.

A bug report describing "the quick share sheet" can mean either, and recon
cannot tell which from the report alone — the two have no code in common, so
guessing wrong burns a whole investigation cycle in the wrong files. The
distinguishing marks: `ShareAlert` has the search field and COPY LINK footer;
`QuickShareSelectorOverlayLayout` does not.

*(Established 2026-09-03.)*

## Bulk reschedule toolbar button

The fork's selection-toolbar "Reschedule" button (`nkactionbarbtn_reschedule`,
`ChatActivity.java:493`) only shows in scheduled-message mode with at least
one row checked (`ChatActivity.java:11492`). Its click handler calls
`performRescheduleSpreadSelectedMessages()` (`ChatActivity.java:4341-4342`),
which collects **every currently-selected id across both message-list slots**
(`ChatActivity.java:37447-37454`) before building the reschedule preview. It
operates on the whole live checkbox set, unlike the single-message
`OPTION_EDIT_SCHEDULE_TIME` above, which only ever touches the one message the
menu was opened on.

*(Established 2026-09-02.)*

## Delay slider and Remember toggle: mode is carried explicitly, not inferred from slider presence

Every schedule sheet's terminal builder (`AlertsCreator.java:4480`) computes
one `naxReschedule` boolean (`:4490`, `isEditSchedule || reschedule != null`)
that both doors above — bulk Reschedule and single-message Edit schedule time —
set `true`. Whether the delay slider block appears at all is gated at `:4761`:
shown for a plain new-message sheet whenever `ScheduleTimeHelper.shouldUseDefaultSchedule`
is true, and shown for a reschedule/edit sheet unless `currentDate` is the
send-when-online sentinel (`0x7FFFFFFE`) — the one case with no real timestamp
to compute a "from now" delay against.

- `ScheduleTimeHelper.RememberToggle.isReschedule` (`ScheduleTimeHelper.java:144`,
  set from the constructor call at `AlertsCreator.java:4691`) drives the
  Remember hint's wording and its show-on-toggle-on condition
  (`ScheduleTimeHelper.java:301`, `:195`) and the slider block's own title
  (`getDelayTitle`, `:313`, called from both the block's initial construction
  at `:365` and its Remember-toggle refresh at `:401`) — Remembered delay when
  Remember is on, otherwise Delay on a reschedule/edit sheet or Default delay
  on a new-message one.
- `addDefaultScheduleSlider`'s own `rescheduleMode` parameter
  (`ScheduleTimeHelper.java:350`, passed as `naxReschedule` from
  `AlertsCreator.java:4771`) gates persistence inside `onSeekBarDrag`
  (`:420-436`): reschedule mode never writes `NaConfig.defaultScheduledTime`
  and never calls `remember.set(false, true)` (which would toggle Remember off
  and clear the remembered offset) — it only refreshes the header and moves
  this sheet's own wheels to `now + selected delay`.

Both flags are always set consistently from the same `naxReschedule` at the
one call site, but they exist as two separate carriers on purpose: one is a
display concern (title/hint text), the other is a persistence concern (what a
drag is allowed to write). Collapsing them back into a single "does this sheet
have a slider" check was the exact bug this fix closed — the slider used to be
the only thing standing in for "is this a reschedule sheet" at all, which broke
the moment reschedule sheets got a slider too.

*(Established 2026-09-04.)*

## Composer Toolbar screen: a `TYPE_INFO` row *is* the gray gap between slider groups

`ComposerLayoutActivity`'s row list (`buildItems()`, `ComposerLayoutActivity.java:397`)
has no dedicated divider row between one slider group (Toolbar size, Icon
spacing, Transparency) and the next. The gray separation comes for free from
the footer row itself: `TYPE_INFO` binds to a bare `TextInfoPrivacyCell` with
no background set (`onBindViewHolder` bind path around `ComposerLayoutActivity.java:653`,
row creation `:599-600`), while every other row type in this screen paints
`key_windowBackgroundWhite` over the fragment's `key_windowBackgroundGray`
background (`:336`, rows at `:593,597,609,613,617`). So removing a `TYPE_INFO`
row doesn't just remove a footer, it also removes the gray seam after it —
two slider groups sharing one footer render as one continuous white block,
which is why the Light/Dark glass sliders were merged onto a single shared
footer under Dark rather than each keeping its own.

*(Established 2026-09-06; citations re-verified against `5b73e5a15c`.)*

## Composer Toolbar screen: `footerText()`'s `default:` arm is a live case, not an error fallback

`footerText(int zone)` (`ComposerLayoutActivity.java:779-797`) returns a
`CharSequence` and switches on either a negative slider-group id or a
`ComposerButtons.ZONE_*` constant. Its `default:` arm returns
`LocaleController.getString(R.string.ComposerLayoutInfo)` — the *resolved*
string, not a bare `@StringRes int`, since the whole method resolves each arm
to a `CharSequence` — and that arm is not a guard for
an impossible value, it's the real, reachable case for `ZONE_HIDDEN`, which
has no explicit `case` label of its own. Deleting or repurposing a `case`
label in this switch without checking what falls through to `default:` is
silent: nothing crashes or fails to compile, a footer just renders under the
wrong row. This is why the Light/Dark glass-transparency merge kept both
`case GROUP_GLASS_LIGHT:` and `case GROUP_GLASS_DARK:` as explicit fall-through
labels sharing one return, instead of deleting one and letting it land in
`default:`. The `GROUP_SPACING` arm is the one that isn't a fixed string: it
delegates to `spacingFooterText()` (`:815-830`), which picks one of three
strings from the saved spacing value against `spacingFloor()`, which is why the
method returns `CharSequence` rather than a `@StringRes int` and why the
packing footer is rebound (with the packing slider) whenever Toolbar size
settles.

*(Established 2026-09-06; footerText signature and disclosure updated 2026-09-06 for #composer-spacing; citations re-verified against `5b73e5a15c`.)*

## Tapping a formatting button on already-styled text toggles it off, via `makeSelectedX`, not `toggleStyleForSelection`

Four surfaces reach the composer's rich-text formatting: the platform text-selection popup
(`ChatActivity.fillActionModeMenu` → `ActionMode.Callback` → `EditTextCaption.performMenuAction(int)`,
`EditTextCaption.java:1243-1273`), the fork's glass composer toolbar
(`ComposerFormattingActions.apply()` → `editText.performMenuAction(...)`), the chat header's
overflow formatting menu (`ChatActivity.java:4609-4671`, calls `makeSelectedBold()` etc.
**directly**, bypassing `performMenuAction` entirely), and physical-keyboard shortcuts split across
two independent paths (`ChatActivityEnterView`'s own `dispatchKeyEvent` →
`toggleStyleForSelection(int)` for one set of keys, and `EditTextCaption.onKeyShortcut` →
`HotkeyController.handleTextStyleShortcut` → `performMenuAction` for another, see
`HotkeyController.java:248-294`). All but the direct-dispatch keyboard path funnel into the
`makeSelectedBold`/`Italic`/`Mono`/`Strike`/`Underline`/`Spoiler`/`Quote`/`Code` methods in
`EditTextCaption.java` — that shared point, not `performMenuAction`, is where `#toggle-formatting`
added toggle-off, because it's the only chokepoint all three non-hotkey-only surfaces share.

`EditTextCaption.toggleStyleForSelection(int)` (`EditTextCaption.java:388-419`) is a **different,
older, keyboard-only** toggle mechanism — an upstream import from commit `80c604047e` ("update to
12.9.0 (6966)", 2026-07-17), wired only to `ChatActivityEnterView`'s `dispatchKeyEvent` path. It was
never reachable from the popup, the toolbar, or the header menu, and `#toggle-formatting`
deliberately does **not** route the six flag-based styles through it for the other three surfaces:
it reads raw `getSelectionStart()/End()` and ignores the `selectionStart`/`selectionEnd` override
those three surfaces rely on, it clears every other inline style when adding Mono (the popup's
plain `makeSelectedMono()` merge does not), and its `addStyle` call hardcodes
entity-intersection=true where the popup/toolbar path respects `allowTextEntitiesIntersection`
(secret chats). Instead, each `makeSelectedX` gained its own removal branch
(`removeStyleIfFullyApplied(int)`, `EditTextCaption.java:175-196`, for the six flag-based styles;
inline containment checks in `makeSelectedQuote`/`makeSelectedCode` for Quote and the language-tagged
Code block) that detects "already fully applied" and removes instead of adding, reusing
`getCurrentStyle(int,int)` (`EditTextCaption.java:966-1000`) and the span-splitting
`addStyle`/`removeStyle` primitives (`EditTextCaption.java:1004-1067`) `toggleStyleForSelection`
already used — so both toggle mechanisms share their span-detection primitives without sharing
selection-resolution or merge semantics. Don't assume a future formatting change can just call
`toggleStyleForSelection` to get toggle behaviour on the popup/toolbar/header-menu surfaces; it
can't, for the reasons above.

*(Established 2026-09-06, `#toggle-formatting`.)*

## A local negative-id row in scheduled_messages_v2 renders in the Scheduled list for free

Established 2026-09-09 (#ghost-hold). A message written to
`scheduled_messages_v2` with a negative `mid` and `send_state = 1` loads and
renders in a chat's Scheduled list with no extra plumbing. Ghost Hold does NOT
use this as its store: held rows live in the fork-owned `ghosthold_<account>.db`
and are injected as display-only objects at
`MessagesController.processLoadedMessages`. The original pre-rebuild design did
persist held rows as negative-id scheduled rows; that path was discarded as
unsafe (it mixed fork state into shared upstream tables), so don't reintroduce
it. This upstream behaviour still matters because it is why a leftover legacy row,
or the stock twin a send-now handoff writes, renders without extra plumbing.
The chain: `MessagesStorage.getMessagesInternal`'s scheduled branch selects
every row for the dialog with no `send_state`/id filter and the id scrub is
gated `message.id > 0` (`MessagesStorage.java:9008`, `:9015`), so a negative-id
row survives load; `MessagesController.processLoadedMessages` skips `id < 0`
when hashing `getScheduledHistory` (`MessagesController.java:12229-12231`), so a
held row can't corrupt scheduled-cache validation; `ChatActivity`'s
`messagesDidLoad` clears the list only for `MODE_DEFAULT`/`MODE_SUGGESTIONS`
(`ChatActivity.java:22475`), so a server scheduled refresh merges rather than
wipes; and server sync deletes only `mid > 0` (`MessagesStorage.java:16269`), so
the local row is not swept. The in-bubble "held" caption is the one thing not
free — it's a fork branch in `ChatMessageCell`'s time-string block
(`ChatMessageCell.java:18692`+).

## isGhostModeActive() is a derived predicate, and the five toggles are flipped individually

Established 2026-09-09 (#ghost-hold). `NekoConfig.isGhostModeActive()` is not a
stored flag: it returns true when any of five independent `ConfigItem` toggles
is on and unlocked (`NekoConfig.java:305-319`). `GhostModeActivity.onItemClick`
flips each one directly (`sendReadMessagePackets` etc.) via `toggleConfigBool()`
without ever calling `toggleGhostMode()` (`GhostModeActivity.java` toggle
handlers). So anything that needs to react to Ghost turning off must watch the
derived true→false edge, not hook `toggleGhostMode()` — a user unchecking the
last active toggle turns Ghost off without that method ever running.

## ChatActivityEnterView is shared by five surfaces; only ChatActivity wires a real fragment and dialogId

`ChatActivityEnterView` is instantiated from five call sites, but only one of
them gives it a real hosting fragment and a real chat to key state on:

- `ChatActivity.java:8645` — passes `this` as the `fragment` constructor
  argument (stored in the `parentFragment` field, declared
  `ChatActivityEnterView.java:811` as `ChatActivity parentFragment`) and later
  (`ChatActivity.java:8894`) calls `setDialogId(long, int)`
  (`ChatActivityEnterView.java:8237`) with the real chat's dialogId. This is
  the only call site where `parentFragment` is ever non-null and `dialog_id`
  (`ChatActivityEnterView.java:812`) is ever the actual open chat.
- `DialogsActivity.java:5075` (forward/share comment field),
  `Gifts/GiftMessageBottomSheet.java:179`, `PopupNotificationActivity.java:317`,
  and `Stories/PeerStoriesView.java:3202` (story reply box) all pass `null` for
  the fragment argument, so `parentFragment` stays null in every one of them —
  `dialog_id` in these instances is either left at 0 or set to something other
  than a real open chat, depending on the surface.

`ChatActivityEnterView` itself already relies on this split throughout — see
the many `parentFragment == null ? ... : parentFragment....` guards scattered
through the class (e.g. `:3993`, `:5262`, `:5504`, `:6549`), each treating a
null `parentFragment` as "this instance isn't the real chat composer." Any new
feature hooking a shared callback on this widget — the typing-time Ghost Mode
reminder added under `#ghost-type-warning` is one example
(`ChatActivityEnterView.java`'s `TextWatcher.onTextChanged`, guarded on
`parentFragment != null` before doing anything chat-specific) — must gate on
`parentFragment != null` the same way, or it will silently also fire from the
share-comment field, the gift-message sheet, the popup-notification reply box,
or the story reply box, none of which represent an actual open chat.

The corollary matters just as much, and cost a design round to work out: those
four surfaces send real messages. Only one of the five instantiations passes a
fragment (`ChatActivity.java:8645`); the other four pass `null` —
`DialogsActivity.java:5075` (share/forward sheet comment),
`Gifts/GiftMessageBottomSheet.java:179`, `Stories/PeerStoriesView.java:3202`
(story reply) and `PopupNotificationActivity.java:317` (notification quick
reply). So a feature hooked on this widget covers only chat-composer sends by
construction, and can never see a story reply, a notification reply, or a
share-sheet comment — nor, since they don't go through any composer at all, a
forward, gallery media, a bot keyboard button, or
`SendMessagesHelper`'s automatic retry of unsent messages on reconnect
(`SendMessagesHelper.java:9095-9114`). Anything that needs to observe *every*
outgoing message needs a hook at the send or network layer, not here; the pair
of Ghost Mode warnings is split along exactly this line.

*(Established 2026-09-10, #ghost-type-warning; corollary added 2026-09-10 in
the same feature's warning-tuning change.)*

## The Scheduled list is reverse-stacked: index 0 of the loaded list renders at the screen bottom

The in-chat message list (Scheduled list included) is laid out by a
`GridLayoutManagerFixed` built with `reverseLayout = !reversed`, and `reversed`
defaults `false` (`ChatActivity.java:7303`, field default `ChatActivity.java:2976`),
so the effective `reverseLayout` is `true`: the item at `messages` index 0 draws
at the **bottom** of the screen and higher indices climb upward. On a normal
(append) load, loaded objects are appended into `messages` in array order after the
load-time sort (`ChatActivity.java:23353-23361`), so the first element of the sorted
list is the bottom-most row on screen. A forward load (`load_type == 1`) reaches the
SAME final order by a different route, not a reversed one: it first reverses the whole
batch (`Collections.reverse(messArr)`, `ChatActivity.java:22977`) and then PREPENDS each
object at index 0 (`:23353`) instead of appending. Reverse-then-prepend-each composes
back to the original sorted order -- `[a,b,c]` -> reverse -> `[c,b,a]` -> prepend c, then
b, then a -> `[a,b,c]` -- so a forward load lands identically to an append load. The
index-0-is-bottom orientation holds either way.

This is the orientation fact behind the Ghost Hold held-row ordering: to make a
block read oldest-at-top / newest-at-bottom, the newest row must sit at the
**lowest** index, not the highest. Getting this backwards produces a fix that
looks right in the code and is still reversed on the device.

*(Established 2026-09-11, #ghost-hold.)*
