package com.radolyn.ayugram.ghosthold;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessageChatArguments;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * "Hold Messages" for Ghost Mode.
 *
 * <p>When Ghost Mode is active and the Hold Messages preference is on, a plain
 * text send is not transmitted. Instead it is persisted in a fork-owned,
 * per-account database ({@code ghosthold_<account>.db}, see {@link GhostHoldStore})
 * that no stock query ever names, so it renders in that chat's Scheduled list and
 * survives an app kill while being structurally invisible to the send path. That
 * invisibility is the no-leak invariant (P1): stock cannot transmit a row it
 * cannot see. Nothing about a held message lives only in memory -- the fork row is
 * the sole source of truth for queue membership.
 *
 * <p>A held message renders through a single read-time injection at the scheduled
 * load chokepoint ({@link #injectHeldScheduled}); the injected objects are
 * display-only and never written back to any stock table.
 *
 * <p>The queue drains ("flush") only when Ghost Mode turns off. Ghost is a
 * derived predicate over five independently-flippable toggles
 * ({@link NekoConfig#isGhostModeActive()}), so the flush is triggered from an
 * edge detector that watches the derived active/inactive transition rather than
 * any single toggle, plus a convergence check at process start. On flush a record
 * is handed back to the normal send funnel by reusing its negative id; the fork
 * record is deleted only once the funnel has provably written its stock row
 * (delete-on-write), after which stock owns delivery and cross-restart retry.
 *
 * <p>Everything here is global (Ghost is a single unsuffixed preference), but the
 * storage and sending sides are per-account: local message ids collide across
 * accounts, so every held record is addressed by {@code (account, mid, dialogId)},
 * never id alone.
 */
public final class GhostHoldController {

    // Distinct fork sentinel for an undated held message. Kept away from
    // upstream's 0x7FFFFFFE "send when online" value (ChatMessageCell), which
    // carries a live upstream meaning we must not collide with.
    public static final int GHOST_HELD_DATE_SENTINEL = 0x7FFFFFFD;

    private static final String PARAM_MARKER = "ghost_hold";
    private static final String PARAM_VALUE = "1";
    // Records that the user disabled link preview for this send, so the flush
    // does not re-enable it (searchLinks defaults to true on the send funnel).
    private static final String PARAM_NO_WEBPAGE = "ghost_hold_no_webpage";
    // The send's repeat period and message effect are not carried on the stored
    // TLRPC.Message, so they ride in params (the marker vehicle) and are restored
    // onto the SendMessageParams on flush. Absent means the send had none.
    private static final String PARAM_REPEAT = "ghost_hold_repeat";
    private static final String PARAM_EFFECT = "ghost_hold_effect";
    // random_id is a client-only field that is not part of the serialized TL blob.
    // The flush re-drive needs it so it reuses this id instead of the funnel minting
    // a fresh one (random_id == 0 guard at SendMessagesHelper ~:4965), which keeps a
    // re-driven send correlated with any prior attempt. Carry it in params and restore
    // it in toHeldItem.
    private static final String PARAM_RANDOM = "ghost_hold_random";
    // Set on a re-driven send after a durable-write failure, so the divert hook
    // lets it through to the network instead of trying to hold it again (which
    // would loop while the write keeps failing). See persistHeld's failure path.
    private static final String PARAM_BYPASS = "ghost_hold_bypass";

    private static final String PREFS_NAME = "ghosthold_state";
    private static final String KEY_LAST_GHOST_ACTIVE = "last_ghost_active";
    // Snapshot of the five ghost toggles captured while Ghost is active, so a
    // cancelled flush restores exactly that per-toggle state instead of force-
    // enabling all of them (setGhostMode(true) would).
    private static final String KEY_GHOST_SNAPSHOT_VALID = "ghost_snapshot_valid";
    private static final String KEY_GHOST_SNAPSHOT_PREFIX = "ghost_snapshot_";

    // Gap between successive sends on flush, so an entire backlog does not leave
    // in the same instant -- a simultaneous burst is itself a signal Ghost ended.
    private static final long FLUSH_STAGGER_MS = 1500;

    private static volatile boolean flushInProgress;

    // NagramX: a synchronous per-account logout barrier for an in-flight
    // flush. The store's own ownership gate (runOwned/generation) only advances when
    // the async teardown actually runs on the fork queue, which can lag the instant
    // logout begins by an arbitrary amount. A flush that pinned its generation before
    // that lag would still pass every store-queue revalidation and transmit the
    // logged-out user's message -- through the reused account slot after a fast
    // re-login (a P1 cross-account leak). This counter is bumped synchronously on the
    // UI thread the moment appDidLogout is observed, before postTeardown() is even
    // queued; the flush captures it at start and rechecks it on the same UI thread
    // immediately before the send, so the two are strictly ordered with no window.
    // AtomicIntegerArray, not a plain int[]: the flush's own reads and the logout bump
    // are all on the UI thread, but the legacy migration (migrateAccount) also has to
    // read it from the storage queue to drop a batch a concurrent logout invalidated,
    // and a plain array element carries no cross-thread visibility guarantee.
    private static final AtomicIntegerArray sessionEpoch = new AtomicIntegerArray(UserConfig.MAX_ACCOUNT_COUNT);

    // NagramX: messages the in-flight flush actually handed back to the send
    // funnel, so the completion bulletin reports what happened rather than what was
    // queued. Only completeHandoff's handed-off branch increments it; a row the user
    // deleted mid-flush, or one re-held because Ghost returned, never does -- so a
    // flush whose only row was deleted reports nothing instead of "1 sent". Reset at
    // performFlush start; one flush runs at a time (flushInProgress).
    private static final AtomicInteger flushSent = new AtomicInteger();

    // NagramX: the per-account session tokens this flush pinned at confirm time
    // (promptFlush's collect callback), stashed for the terminal completion report.
    // onItemTerminal reads global flushSent and publishes a bulletin against the
    // current LaunchActivity fragment; if a logout reused an account slot mid-flush
    // that fragment now belongs to a different session, so the report must be
    // suppressed. Held as a field rather than threaded through the ~11 onItemTerminal
    // call sites for the same reason flushSent is: one flush runs at a time
    // (flushInProgress), and the terminal callback validates it synchronously on the
    // UI thread -- the thread logout bumps sessionEpoch on -- before the async count.
    private static volatile java.util.Map<Integer, Integer> flushSessionByAccount;

    // Set once we register the foreground retry below, so it is never added twice.
    private static volatile boolean foregroundRetryArmed;

    private GhostHoldController() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE);
    }

    // ---- predicates ----

    public static boolean isHoldActive() {
        return NekoConfig.isGhostModeActive() && NekoConfig.holdMessagesWhileGhost.Bool();
    }

    public static boolean isHeld(@Nullable MessageObject mo) {
        return mo != null && isHeldMessage(mo.messageOwner);
    }

    public static boolean isHeldMessage(@Nullable TLRPC.Message m) {
        return m != null && m.params != null && PARAM_VALUE.equals(m.params.get(PARAM_MARKER));
    }

    // ---- the send-time hook ----

    /**
     * Consulted once, at the single {@code sendMessage(SendMessageParams)} funnel.
     * Reads Ghost state and the hold preference as one act and, when a plain text
     * message should be held, persists it and returns true so the caller returns
     * before any in-flight send state is created.
     */
    public static boolean maybeHold(int account, long peer, SendMessagesHelper.SendMessageParams params) {
        // Re-arm this account lazily. initAccount is guarded by accountInited and is
        // a cheap boolean check after the first run, but on an in-process re-login
        // (LoginActivity reuses the slot without going back through
        // ApplicationLoader) checkOnProcessStart never runs again, so the fork
        // observers would stay unregistered. Re-arming at the send chokepoint puts
        // them back before the first held send of the new session.
        initAccount(account);
        if (!isHoldableTextSend(account, peer, params)) {
            return false;
        }
        return persistHeld(account, peer, params);
    }

    /**
     * The hold allowlist: true only for a plain text send whose every property we
     * can persist on the stored {@link TLRPC.Message} (or carry in its params) and
     * restore faithfully on flush.
     *
     * <p>This is deliberately an allowlist, not a denylist of known media fields.
     * A denylist fails unsafe -- a future SendMessageParams field nobody here has
     * heard of would be held and silently degraded on flush. An allowlist fails
     * safe: an unrecognised send simply is not held, falls through to the normal
     * send path (where the send-exposure warning tells the user their status was
     * exposed), and is never corrupted. When teaching hold a new field, persist AND
     * restore it first, then relax the matching guard here -- never the reverse.
     *
     * <p>Held faithfully: message text, entities, the full reply header
     * (reply_to_msg_id, top/forum, quote + quote entities), invert_media, the
     * silent flag, a user-picked schedule date, scheduleRepeatPeriod, effect_id,
     * and link-preview suppression (searchLinks). Everything else excludes the send.
     */
    private static boolean isHoldableTextSend(int account, long peer, @Nullable SendMessagesHelper.SendMessageParams p) {
        if (p == null || !isHoldActive()) {
            return false;
        }
        // A re-drive after a failed durable write is explicitly not to be held.
        if (p.params != null && PARAM_VALUE.equals(p.params.get(PARAM_BYPASS))) {
            return false;
        }
        // A flush re-drive carries retryMessageObject; it must never be re-held.
        if (p.retryMessageObject != null) {
            return false;
        }
        // Secret chats have their own send machinery and lifetime; leave them alone.
        if (DialogObject.isEncryptedDialog(peer)) {
            return false;
        }
        // Must be a text send: text present, no media of any kind.
        if (p.message == null) {
            return false;
        }
        if (p.location != null || p.photo != null || p.videoEditedInfo != null
                || p.document != null || p.game != null || p.poll != null
                || p.pollSendParams != null || p.todo != null || p.invoice != null
                || p.mediaWebPage != null || p.cover != null || p.user != null
                || p.richMessage != null || p.sendingStory != null) {
            return false;
        }
        // The chat-arguments bundle is ALWAYS attached on a composer send:
        // ChatActivity.getMessageChatSendParams() builds a fresh non-null object
        // even for an ordinary chat, so `sendMessageChatArguments != null` carries
        // no information and would refuse every normal message. Test its fields
        // instead. A welcome-message send redirects the peer, and a quick-reply
        // (business) send carries its shortcut here -- the funnel reads
        // quickReplyShortcut from this bundle when p.quick_reply_shortcut is unset
        // and never writes it back, so the direct p.quick_reply_shortcut test below
        // does not see it. Neither is persisted, so exclude both; an empty bundle
        // (the normal case) is holdable.
        SendMessageChatArguments chatArgs = p.sendMessageChatArguments;
        if (chatArgs != null && (chatArgs.welcomeMessageChatId != 0
                || chatArgs.quickReplyShortcut != null
                || chatArgs.quickReplyShortcutId != 0)) {
            return false;
        }
        // Metadata we do not persist and restore -> refuse rather than degrade:
        //  reply markup (an inline keyboard attached to the send),
        //  a story-reply target,
        //  a poll-vote or todo-task reply quote (replyQuote.poll / replyQuote.todo):
        //    the funnel writes reply_to.poll_option / todo_item_id from these
        //    (SendMessagesHelper ~:5065-5068), but persistHeld only carries a plain
        //    text quote, so a held poll-vote/todo reply would flush as an ordinary
        //    reply -- changing what the message *does*, not just how it looks. A
        //    plain text-quote reply IS persisted, so it stays holdable,
        //  a quick-reply shortcut (business) set directly on the params,
        //  a monoforum destination peer,
        //  suggestion params (suggested posts),
        //  a non-zero dice stake,
        //  a manually-resolved link preview (the auto preview is regenerated on
        //    flush via searchLinks; a user-edited webPage is not, so refuse it),
        //  a per-message self-destruct timer,
        //  (an ephemeral receiver is refused in its own block below, since the
        //    funnel derives it from more than the explicit field),
        //  a bare dice-emoji message (text is in MessagesController.diceEmojies): the
        //    funnel converts such a send into a dice *media* message when
        //    canSendGames is true (SendMessagesHelper ~:4664). Our hook runs before
        //    that conversion, so it still looks like plain text here, and of()
        //    restores canSendGames = true on flush -- so a held dice emoji becomes
        //    media then, out of v1 scope and arriving by a path nobody chose. Keyed
        //    on the funnel's own trigger (the emoji text), not on canSendGames:
        //    canSendGames gates the conversion but is also true for every ordinary
        //    send, so testing !canSendGames would refuse the non-converting case and
        //    admit the converting one,
        //  an explicit pangu override (canUsePangu != null): of() restores null (the
        //    config default), so a held text with pangu forced on/off would be spaced
        //    differently on flush. A normal send leaves canUsePangu == null, so the
        //    test is not vacuous; null means "apply the pangu setting in force when
        //    this sends", which for a deferred hold is flush time -- the field
        //    behaving as specified, so it is documented rather than excluded.
        if (p.replyMarkup != null
                || p.replyToStoryItem != null
                || (p.replyQuote != null && (p.replyQuote.poll || p.replyQuote.todo))
                || p.quick_reply_shortcut != null || p.quick_reply_shortcut_id != 0
                || p.monoForumPeer != 0
                || p.suggestionParams != null
                || p.dice_stake != 0
                || p.webPage != null
                || p.ttl != 0
                || isDiceEmojiText(account, p.message)
                || p.canUsePangu != null) {
            return false;
        }
        // Ephemeral receiver: the funnel picks the ephemeral target from three
        // sources (SendMessagesHelper ~:4470-4476) and our hook runs before that
        // pick. We persist none of them and of() cannot rebuild an ephemeral send, so
        // a held ephemeral message would flush as an ordinary, non-vanishing one -- a
        // privacy degradation, not a cosmetic one. The explicit ephemeralReceiverBotId
        // field is only one source: also refuse a reply to an ephemeral message and
        // an ephemeral slash command. The command case is keyed on the funnel's own
        // getEphemeralCommandBotId -- a side-effect-free lookup that returns 0 for any
        // text not starting with '/' or a non-chat peer -- so the two cannot disagree.
        if (p.ephemeralReceiverBotId != 0
                || (p.replyToMsg != null && p.replyToMsg.isEphemeral())
                || org.telegram.messenger.utils.EphemeralMessagesHelper.getInstance(account).getEphemeralCommandBotId(p.message, peer) != 0) {
            return false;
        }
        // Send-as identity: a channel/megagroup post can resolve a non-self sender
        // (a linked channel, an anonymous admin, a broadcast identity). persistHeld
        // hardcodes from_id = self, so holding such a send would flush it under the
        // wrong identity. Mirror the funnel's resolution and refuse a non-self one.
        if (resolvesNonSelfSendAs(account, peer)) {
            return false;
        }
        // Paid direct messages: holding one defers a payment to a later moment the
        // user did not choose (a flush triggered by toggling Ghost off), possibly at
        // a price that changed while it sat, and pops the Stars paywall then. The
        // paywall (AlertsCreator.ensurePaidMessageConfirmation / showPayForMessageAlert)
        // exposes no cancel signal, so a deferred flush item could not be released on
        // a dismissed dialog either. Refuse to hold: send now, at the price the user
        // saw, and let the send-exposure warning tell them their status was exposed.
        // The same check runs again at flush time (dispatchFreshItem) so that a dialog
        // which becomes paid *after* it was held is not auto-re-driven into a paywall:
        // an automated flush never opens a paywall.
        if (isPaidDialog(account, peer)) {
            return false;
        }
        // Cross-chat reply: replying to a message in a DIFFERENT dialog than the send
        // target (SendMessagesHelper:5152-5183). The funnel sets reply_to_peer_id and
        // converts the whole thing into a quote-reply -- pulling in quote text,
        // entities, reply_media and offset. persistHeld only captures a same-chat reply
        // header (reply_to_msg_id and, for forums, the topic), so on flush the funnel's
        // retry path would ship the stored header verbatim with no reply_to_peer_id:
        // the reply would resolve against the wrong chat or break. Faithfully persisting
        // the funnel's quote-conversion means replicating a large, subtle slice of that
        // logic on a correctness-critical path -- high risk for a small, uncommon set
        // (only replies targeting another chat; ordinary same-chat replies stay
        // holdable). So refuse rather than degrade, the same disposition we take for
        // media, ephemeral and poll/todo replies: the message is sent now, exactly as
        // composed, and the send-exposure warning speaks.
        if (p.replyToMsg != null && p.replyToMsg.getDialogId() != peer) {
            return false;
        }
        // A same-dialog cross-topic forum reply is the other half of that trap. In a
        // forum the funnel takes its anotherTopic path (SendMessagesHelper:5164-5180)
        // and converts the reply into a quote just like the cross-chat case -- setting
        // reply_to_peer_id and pulling in generated quote text/entities/media.
        // persistHeld stores none of that and of(mo) has no replyToMsg to make the
        // funnel repeat it, so a held cross-topic reply would flush without the
        // conversion -- something other than what was held. Detect it with the funnel's
        // own test (forum dialog, reply target is not the topic root and sits in a
        // different topic), using the params it carries, and refuse with the same
        // disposition as the cross-chat case.
        if (p.replyToMsg != null && p.replyToTopMsg != null) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-peer);
            if (ChatObject.isForum(chat)
                    && p.replyToTopMsg.getId() != p.replyToMsg.getId()
                    && MessageObject.getTopicId(account, p.replyToMsg.messageOwner, true) != p.replyToTopMsg.getId()) {
                return false;
            }
        }
        // Fail-closed backstop for the remaining fields no explicit guard above
        // covers: refuse unless each of them is at its default, and refuse if the
        // param class grew a field upstream that this method has not been taught. A
        // new field then defaults to "refuse to hold" (send now, correctly, and let
        // the exposure warning speak) rather than "hold and mangle".
        if (!onlyPersistedFieldsSet(p)) {
            return false;
        }
        return true;
    }

    // Count of declared instance fields on SendMessageParams this method was written
    // against (SendMessagesHelper.java, the `public static class SendMessageParams`
    // block). It is the name-independent half of the backstop below: R8 keeps every
    // field of this class -- `-keep class org.telegram.messenger.* { *; }`
    // (proguard-rules.pro:9) covers the nested SendMessagesHelper$SendMessageParams,
    // verified against the 377d89c minified APK (docs/codemap/upstream-traps.md, 2026-
    // 09-10), so the count is identical in a debug and a minified build. An upstream
    // merge that adds or drops a field changes this count, which trips the backstop
    // and refuses to hold until the new field is triaged here.
    private static final int KNOWN_PARAM_FIELD_COUNT = 54;

    // The actual declared instance-field count in this build, computed once at class
    // load from the class literal -- not per send, and not off p.getClass(), since
    // SendMessageParams is never subclassed and the count must be the one for exactly
    // that class. Compared against KNOWN_PARAM_FIELD_COUNT so an upstream field add or
    // drop trips the backstop, with no per-send reflection or allocation.
    private static final int ACTUAL_PARAM_FIELD_COUNT =
            countInstanceFields(SendMessagesHelper.SendMessageParams.class);

    /**
     * The fail-closed half of {@link #isHoldableTextSend}: true iff no SendMessageParams
     * field the explicit guards above do not already cover is carrying non-default state
     * we would silently drop when {@code of()} rebuilds the message on flush.
     *
     * <p>The field reads here are compile-checked references, not name lookups: an
     * upstream rename or removal of any field named below breaks the build rather than
     * silently disabling holding, and R8 rewrites each reference in lockstep with the
     * field, so this behaves identically in a debug and a minified build. (The prior
     * version matched {@code Field.getName()} against a hardcoded name set; that is
     * invisible to the compiler and would silently disable the feature under any keep
     * rule that let R8 rename these fields.) The trailing count guard is the only piece
     * that must run at runtime, because no compile-time reference can detect a field
     * ADDED upstream that this method has never seen -- a count mismatch means exactly
     * that, and refuses to hold, preserving the original fail-closed intent while
     * confining it to a genuine unknown field rather than a reflection failure.
     *
     * <p>{@code sendAnimationData} and {@code updateStickersOrder} are deliberately not
     * grounds to refuse. Neither is part of the sent message: sendAnimationData is a
     * transient composer fly-in hint (on-screen position and size, rebuilt fresh at send
     * time) and is non-null on every ordinary composer send; updateStickersOrder only
     * bumps recently-used custom emoji in the local picker. A held send plays no composer
     * animation and reorders nothing at hold time, so dropping both changes nothing about
     * what flushes. Treating sendAnimationData as a blocker was the bug this fixes: it is
     * non-null on 100% of composer sends, so the backstop refused every ordinary message
     * and it fell through to the network with Ghost on.
     */
    private static boolean onlyPersistedFieldsSet(SendMessagesHelper.SendMessageParams p) {
        // Fields we neither persist nor can faithfully rebuild on flush, and which no
        // guard above already tests. Any one non-default marks a send we must not hold.
        if (p.caption != null
                || p.path != null
                || p.parentObject != null
                || p.pollIndex != 0
                || p.hasMediaSpoilers
                || p.sendingHighQuality
                || p.isLivePhoto
                || p.livePhotoTimestamp != 0
                || p.stars != 0
                || p.payStars != 0
                || p.richMessageInputUsers != null) {
            return false;
        }
        // Name-independent add-detector: a field appearing (or disappearing) upstream
        // changes the declared instance-field count, so refuse until it is triaged.
        return ACTUAL_PARAM_FIELD_COUNT == KNOWN_PARAM_FIELD_COUNT;
    }

    private static int countInstanceFields(Class<?> cls) {
        int n = 0;
        for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                n++;
            }
        }
        return n;
    }

    /**
     * True if sending to {@code peer} would require a Stars payment. Mirrors the
     * funnel's own paid check ({@link SendMessagesHelper} ~:4458-4462) so the hold
     * predicate, the flush-time re-check, and the funnel cannot disagree about what
     * "paid" means.
     */
    private static boolean isPaidDialog(int account, long peer) {
        MessagesController controller = MessagesController.getInstance(account);
        long payStars = controller.getSendPaidMessagesStars(peer);
        if (payStars <= 0) {
            payStars = DialogObject.getMessagesStarsPrice(controller.isUserContactBlocked(peer));
        }
        return payStars > 0;
    }

    /**
     * True if {@code message} is a bare dice emoji that the funnel would turn into
     * a dice media message ({@link SendMessagesHelper} ~:4664). Keyed on the same
     * {@link MessagesController#diceEmojies} set the funnel branches on, so the
     * hold predicate and the funnel cannot disagree about what a dice send is.
     * Null-guards the set (the funnel reaches ~:4664 only under conditions that
     * imply it is loaded; our hook runs on every send, so guard it here).
     */
    private static boolean isDiceEmojiText(int account, @Nullable String message) {
        if (message == null) {
            return false;
        }
        java.util.Set<String> dice = MessagesController.getInstance(account).diceEmojies;
        return dice != null && dice.contains(message.replace("\ufe0f", ""));
    }

    /**
     * True if a send to {@code peer} would resolve a send-as sender other than this
     * account's own user. Mirrors the funnel, which only applies send-as for a
     * channel input peer ({@link SendMessagesHelper} ~:4518); a user DM or a basic
     * group never does.
     */
    private static boolean resolvesNonSelfSendAs(int account, long peer) {
        if (peer >= 0) {
            return false;
        }
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Chat chat = controller.getChat(-peer);
        if (chat == null || !ChatObject.isChannel(chat)) {
            return false;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        return ChatObject.getSendAsPeerId(chat, controller.getChatFull(-peer), true) != selfId;
    }

    private static boolean persistHeld(int account, long peer, SendMessagesHelper.SendMessageParams params) {
        final MessagesController controller = MessagesController.getInstance(account);
        final UserConfig userConfig = UserConfig.getInstance(account);

        TLRPC.TL_message msg = new TLRPC.TL_message();
        msg.message = params.message;
        if (params.entities != null && !params.entities.isEmpty()) {
            msg.entities = params.entities;
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
        msg.media = new TLRPC.TL_messageMediaEmpty();
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        msg.local_id = msg.id = userConfig.getNewMessageId();
        userConfig.saveConfig(false);
        msg.out = true;
        msg.from_id = new TLRPC.TL_peerUser();
        msg.from_id.user_id = userConfig.getClientUserId();
        msg.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        msg.peer_id = controller.getPeer(peer);
        msg.dialog_id = peer;
        msg.random_id = SendMessagesHelper.getInstance(account).getNextRandomId();
        msg.silent = !params.notify || MessagesController.getNotificationsSettings(account).getBoolean("silent_" + peer, false);
        msg.invert_media = params.invert_media;
        // A user-picked schedule date is recorded and shown but has no enforcement
        // power while held; an undated hold sorts under its own sentinel header.
        msg.date = params.scheduleDate != 0 ? params.scheduleDate : GHOST_HELD_DATE_SENTINEL;
        msg.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
        msg.unread = true;
        msg.attachPath = "";

        // Reconstruct the full reply header, not just reply_to_msg_id: the flush
        // re-drive reuses this stored message verbatim (of(MessageObject) passes a
        // null replyToMsg, so the funnel keeps whatever reply_to is on the row), so
        // a topic/quote reply that isn't captured here is silently dropped on send.
        // Mirrors the funnel's own header construction (SendMessagesHelper ~:5031).
        MessageObject replyToMsg = params.replyToMsg;
        MessageObject replyToTopMsg = params.replyToTopMsg;
        ChatActivity.ReplyQuote replyQuote = params.replyQuote;
        if (replyQuote != null && replyQuote.message != null && replyToMsg != null) {
            replyToMsg = replyQuote.message;
        }
        if (replyToMsg != null && (replyToTopMsg == null || replyToMsg != replyToTopMsg || replyToTopMsg.getId() != 1)) {
            msg.reply_to = new TLRPC.TL_messageReplyHeader();
            msg.flags |= TLRPC.MESSAGE_FLAG_REPLY;
            msg.reply_to.flags |= 16;
            msg.reply_to.reply_to_msg_id = replyToMsg.getId();
            if (replyToTopMsg != null && replyToTopMsg != replyToMsg && replyToTopMsg.getId() != 1) {
                msg.reply_to.reply_to_top_id = replyToTopMsg.getId();
                msg.reply_to.flags |= 2;
                if (replyToTopMsg.isTopicMainMessage) {
                    msg.reply_to.forum_topic = true;
                    msg.reply_to.flags |= 8;
                }
            } else if (replyToMsg.isTopicMainMessage) {
                msg.reply_to.forum_topic = true;
                msg.reply_to.flags |= 8;
            }
            if (replyQuote != null && !replyQuote.todo && !replyQuote.poll) {
                msg.reply_to.quote_text = replyQuote.getText();
                if (!android.text.TextUtils.isEmpty(msg.reply_to.quote_text)) {
                    msg.reply_to.quote = true;
                    msg.reply_to.flags |= 64;
                    msg.reply_to.flags |= 1024;
                    msg.reply_to.quote_offset = replyQuote.start;
                    ArrayList<TLRPC.MessageEntity> quoteEntities = replyQuote.getEntities();
                    if (quoteEntities != null && !quoteEntities.isEmpty()) {
                        msg.reply_to.quote_entities = new ArrayList<>(quoteEntities);
                        msg.reply_to.flags |= 128;
                    }
                }
            }
        }

        HashMap<String, String> stored = params.params != null ? new HashMap<>(params.params) : new HashMap<>();
        stored.put(PARAM_MARKER, PARAM_VALUE);
        if (!params.searchLinks) {
            stored.put(PARAM_NO_WEBPAGE, PARAM_VALUE);
        }
        // scheduleRepeatPeriod and effect_id live only on SendMessageParams, not on
        // the stored TLRPC.Message, so carry them here and restore them on flush.
        if (params.scheduleRepeatPeriod != 0) {
            stored.put(PARAM_REPEAT, Integer.toString(params.scheduleRepeatPeriod));
        }
        if (params.effect_id != 0) {
            stored.put(PARAM_EFFECT, Long.toString(params.effect_id));
        }
        // Carry random_id so toHeldItem can restore it (the blob does not hold it).
        // msg.random_id was just minted above and is always non-zero.
        stored.put(PARAM_RANDOM, Long.toString(msg.random_id));
        msg.params = stored;

        final TLRPC.Message stableMsg = msg;
        final byte[] blob;
        try {
            blob = GhostHoldStore.encode(stableMsg);
        } catch (Exception e) {
            // Serializing a plain-text message does not fail in practice; if it
            // somehow does we must not hold a message we could not persist. Return
            // false so maybeHold falls through to the normal send path, where the
            // message is actually sent (and the send-exposure warning fires) rather
            // than being silently swallowed.
            FileLog.e(e);
            return false;
        }
        final SendMessagesHelper.SendMessageParams originalParams = params;
        final GhostHoldStore store = GhostHoldStore.getInstance(account);
        final GhostHoldStore.HeldRecord record =
                new GhostHoldStore.HeldRecord(stableMsg.id, peer, stableMsg.date, GhostHoldStore.STATE_HELD, blob);
        // Critical: the durable fork row must exist before the user is told "Held".
        // insertOnQueue writes ghost_held on the store's serial queue; the interface
        // update, the Scheduled-list insert and the bulletin are chained after it so
        // they run only once the row has landed. This orders the signal after
        // durability without blocking the UI thread on a synchronous DB write.
        //
        // Pin the generation and pass onInvalidated: if a logout queued postTeardown()
        // ahead of this op, the teardown bumps the generation before we run and the
        // insert is dropped by the ownership gate. A null callback there would lose the
        // message silently -- the funnel already stood down on our promise to hold it,
        // yet no row is written (P2, the highest-severity loss). But the generation only
        // ever moves on logout teardown, so an invalidation here means the account slot
        // is being torn down: re-driving originalParams through sendMessage(account) now
        // would transmit through whoever next owns the slot -- the very cross-account
        // leak the session-epoch barrier guards. So we cannot honour NOT LOST by redriving, because the
        // redrive would not send THIS user's message, it would send from the wrong
        // account. Route both failure paths through redriveAfterPersistFailure, which
        // redrives only when the session epoch is unchanged (a same-session disk-full:
        // NOT LOST) and abandons with a log when it moved (logout raced in: NOT LEAKED).
        // Fail toward NOT LOST, then NOT LEAKED.
        final int epoch = store.currentGeneration();
        final int holdSession = sessionEpoch.get(account);
        store.runOwned(epoch, () -> {
            boolean ok = store.insertOnQueue(record);
            if (!ok) {
                // The durable write failed (e.g. disk full). We already told the
                // funnel we would hold this send, so it did nothing; if we also drop
                // it here the user's message is lost (P2). Re-drive it through the
                // normal send path with the hold bypassed so it is actually sent --
                // unless a logout has begun since we captured holdSession, which the
                // guarded helper checks.
                AndroidUtilities.runOnUIThread(() -> redriveAfterPersistFailure(account, originalParams, holdSession));
                return;
            }
            postScheduledCount(account, peer, holdSession);
            AndroidUtilities.runOnUIThread(() -> {
                // NagramX (success side): this hop publishes stableMsg and the
                // divert bulletin through the account's MessagesController. If logout
                // began while it was queued, the slot may already belong to the next
                // user, so rendering here would surface the previous session's held
                // message in the new account's UI. sessionEpoch moved synchronously the
                // instant logout began and this runnable is on the UI thread with it, so
                // the two are strictly ordered with no race -- skip the UI publish if the
                // epoch moved. The durable row is handled by the ownership gate/teardown
                // regardless; this guards only what the user sees.
                if (sessionEpoch.get(account) != holdSession) {
                    return;
                }
                MessageObject mo = new MessageObject(account, stableMsg, true, true);
                mo.scheduled = true;
                ArrayList<MessageObject> objArr = new ArrayList<>();
                objArr.add(mo);
                controller.updateInterfaceWithMessages(peer, objArr, 1);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
                showDivertBulletin();
            });
        }, () -> AndroidUtilities.runOnUIThread(() -> redriveAfterPersistFailure(account, originalParams, holdSession)));
        return true;
    }

    /**
     * Last-resort recovery when the durable hold write failed: send the original
     * message the normal way, with {@link #PARAM_BYPASS} set so the divert hook does
     * not try to hold it again. Losing the message would violate P2, so a message we
     * could not hold is sent rather than dropped -- but only while the account slot
     * still belongs to the user who sent it.
     *
     * <p>{@code holdSession} is the {@link #sessionEpoch} captured when the hold was
     * accepted. Runs on the UI thread, where appDidLogout bumps that epoch, so the two
     * are strictly ordered. If the epoch has moved a logout is under way and the slot
     * is being reused: redriving would push this user's message through the next
     * owner's account (a P1 cross-account leak), which does not preserve the message
     * anyway. So we abandon and log instead -- NOT LEAKED wins here because the redrive
     * cannot satisfy NOT LOST for the original sender.
     */
    private static void redriveAfterPersistFailure(int account, SendMessagesHelper.SendMessageParams params, int holdSession) {
        if (sessionEpoch.get(account) != holdSession) {
            FileLog.e("ghostHold: hold persist failed but logout raced in; abandoning redrive for account " + account + " to avoid cross-account send");
            return;
        }
        if (params.params == null) {
            params.params = new HashMap<>();
        }
        params.params.put(PARAM_BYPASS, PARAM_VALUE);
        SendMessagesHelper.getInstance(account).sendMessage(params);
    }

    private static void showDivertBulletin() {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.GhostHoldDiverted)).show();
    }

    // ---- ghost-off edge detection ----

    /**
     * Invoked wherever Ghost state can change (the aggregate toggle and each of
     * the five individual toggles). Compares the persisted previous active state
     * against the current derived value and, on a true -> false edge, requests a
     * flush. Idempotent: a repeated call with no transition does nothing.
     */
    public static synchronized void onGhostStateMaybeChanged() {
        boolean nowActive = NekoConfig.isGhostModeActive();
        boolean wasActive = prefs().getBoolean(KEY_LAST_GHOST_ACTIVE, false);
        SharedPreferences.Editor editor = prefs().edit();
        editor.putBoolean(KEY_LAST_GHOST_ACTIVE, nowActive);
        if (nowActive) {
            // Remember the exact toggle configuration while Ghost is active; a
            // cancelled flush restores precisely this, not an all-toggles-on state.
            writeGhostSnapshot(editor);
        }
        editor.apply();
        if (wasActive && !nowActive) {
            AndroidUtilities.runOnUIThread(GhostHoldController::promptFlush);
        }
    }

    /**
     * Convergence check at process start. If Ghost ended (or was never on) while a
     * backlog remained -- e.g. the app was killed after Ghost went off but before the
     * flush confirmation was accepted -- re-offer the same "Send held messages?"
     * confirmation so the queue is not stranded across launches. It prompts, never
     * drains silently: the confirmation is a UX guarantee dazewell approved, so a
     * flush with no foreground screen is deferred to a later launch that has one, not
     * skipped. This can re-prompt a flush that was confirmed then interrupted, because
     * we deliberately do not persist a "confirmed" marker to tell that apart from
     * "never confirmed" -- asking twice is cheap, sending unasked is not. Also seeds
     * the edge baseline.
     */
    public static void checkOnProcessStart() {
        boolean nowActive = NekoConfig.isGhostModeActive();
        // Per-account bring-up, before any flush prompt below can collect: register
        // the fork observers, migrate any legacy held rows out of the stock tables
        // into ghost_held, and reconcile a flush interrupted by a kill. All three
        // are enqueued here so they are ordered ahead of the collect that promptFlush
        // triggers; each is idempotent and safe to re-run on a later launch.
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                initAccount(a);
            }
        }
        SharedPreferences.Editor editor = prefs().edit();
        editor.putBoolean(KEY_LAST_GHOST_ACTIVE, nowActive);
        if (nowActive) {
            writeGhostSnapshot(editor);
        }
        editor.apply();
        if (!nowActive) {
            AndroidUtilities.runOnUIThread(GhostHoldController::promptFlush);
        }
    }

    // ---- flush ----

    private static void promptFlush() {
        if (flushInProgress) {
            return;
        }
        // Claim the flush before the async collect, not after: collectHeld hops to
        // the storage queue and back, and two ghost-off edges in that window would
        // otherwise both pass this guard, collect the same rows and open two
        // confirmations -> duplicate sends. All flushInProgress access is on the UI
        // thread, so a plain boolean serialises correctly. Cleared on empty, cancel,
        // or completion (performFlush).
        flushInProgress = true;
        collectHeld(items -> {
            if (items.isEmpty()) {
                flushInProgress = false;
                return;
            }
            int pending = items.size();
            BaseFragment fragment = LaunchActivity.getLastFragment();
            if (fragment == null || fragment.getParentActivity() == null) {
                // No foreground screen to confirm on (e.g. process-start convergence
                // before any UI is up, including a headless push-service process whose
                // only checkOnProcessStart() runs with no Activity). Defer, never drain:
                // a backlog must not leave without the user seeing the "Send held
                // messages?" dialog. Release the claim and leave every row held, then
                // arm a one-time foreground retry so the same confirmation is re-offered
                // the moment a screen next exists in this process -- otherwise, since
                // checkOnProcessStart() is the only startup call site, foregrounding
                // later in the same process would never retry. Deferred, not skipped --
                // a user who keeps dismissing keeps deferring, which is their choice
                // (nothing is lost, the rows stay visible in Scheduled).
                flushInProgress = false;
                armForegroundRetry();
                return;
            }

            int chats = countDistinctChats(items);
            // Pin each account's store generation and synchronous session epoch now, at
            // dialog creation on the UI thread -- before the user can leave the confirm
            // dialog up and trigger a logout. flushItem revalidates against these; a
            // logout during the dialog's lifetime bumps both, so the recheck sees the
            // mismatch and abandons the send rather than transmitting the previous
            // session's rows through a reused account slot.
            final java.util.HashMap<Integer, Integer> epochByAccount = new java.util.HashMap<>();
            final java.util.HashMap<Integer, Integer> sessionByAccount = new java.util.HashMap<>();
            for (HeldItem it : items) {
                if (!epochByAccount.containsKey(it.account)) {
                    epochByAccount.put(it.account, GhostHoldStore.getInstance(it.account).currentGeneration());
                    sessionByAccount.put(it.account, sessionEpoch.get(it.account));
                }
            }
            String body;
            if (pending == 1) {
                body = LocaleController.getString(R.string.GhostHoldFlushConfirmOne);
            } else {
                body = LocaleController.formatString(R.string.GhostHoldFlushConfirmMany, pending, chats);
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
            builder.setTitle(LocaleController.getString(R.string.GhostHoldFlushConfirmTitle));
            builder.setMessage(body);
            builder.setPositiveButton(LocaleController.getString(R.string.MessageScheduleSend), (dialog, which) -> performFlush(items, epochByAccount, sessionByAccount));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> {
                flushInProgress = false;
                restoreGhost();
            });
            builder.setOnCancelListener(dialog -> {
                flushInProgress = false;
                restoreGhost();
            });
            builder.show();
        });
    }

    /**
     * Register a once-per-process foreground listener that re-offers a deferred flush
     * the next time the app has a foreground screen. Called only when a flush was
     * deferred for want of a UI (see promptFlush's no-fragment branch); reuses the
     * app-wide ForegroundDetector so no lifecycle hook is added to a base file. The
     * listener stays for the process lifetime: if the user dismisses the re-offered
     * dialog it defers again, and the next foreground re-offers once more. The retry
     * is gated on Ghost being off -- while Ghost is on the backlog must stay held, so
     * there is nothing to flush and we skip the collect entirely.
     */
    private static void armForegroundRetry() {
        if (foregroundRetryArmed) {
            return;
        }
        foregroundRetryArmed = true;
        try {
            org.telegram.ui.Components.ForegroundDetector detector = org.telegram.ui.Components.ForegroundDetector.getInstance();
            if (detector == null) {
                foregroundRetryArmed = false;
                return;
            }
            detector.addListener(new org.telegram.ui.Components.ForegroundDetector.Listener() {
                @Override
                public void onBecameForeground() {
                    if (!NekoConfig.isGhostModeActive()) {
                        AndroidUtilities.runOnUIThread(GhostHoldController::promptFlush);
                    }
                }

                @Override
                public void onBecameBackground() {
                }
            });
        } catch (Exception e) {
            foregroundRetryArmed = false;
            FileLog.e(e);
        }
    }

    private static tw.nekomimi.nekogram.config.ConfigItem[] ghostToggleItems() {
        return new tw.nekomimi.nekogram.config.ConfigItem[]{
                NekoConfig.sendReadMessagePackets,
                NekoConfig.sendReadStoriesPackets,
                NekoConfig.sendOnlinePackets,
                NekoConfig.sendUploadProgress,
                NekoConfig.sendOfflinePacketAfterOnline,
        };
    }

    private static void writeGhostSnapshot(SharedPreferences.Editor editor) {
        tw.nekomimi.nekogram.config.ConfigItem[] items = ghostToggleItems();
        for (tw.nekomimi.nekogram.config.ConfigItem item : items) {
            // Key the snapshot by the toggle's own stable config key, never by array
            // index: a future reorder or insertion in ghostToggleItems() would otherwise
            // silently restore the wrong privacy toggle on cancel.
            editor.putBoolean(KEY_GHOST_SNAPSHOT_PREFIX + item.getKey(), item.Bool());
        }
        editor.putBoolean(KEY_GHOST_SNAPSHOT_VALID, true);
    }

    private static void restoreGhost() {
        if (prefs().getBoolean(KEY_GHOST_SNAPSHOT_VALID, false)) {
            // Cancel means "keep holding". Re-activate Ghost by restoring the exact
            // per-toggle state captured while it was last active, writing back only the
            // toggles that actually changed -- so we never switch on a toggle the user
            // left off. setGhostMode(true) would force all five into ghost state.
            tw.nekomimi.nekogram.config.ConfigItem[] items = ghostToggleItems();
            for (tw.nekomimi.nekogram.config.ConfigItem item : items) {
                boolean prior = prefs().getBoolean(KEY_GHOST_SNAPSHOT_PREFIX + item.getKey(), item.Bool());
                if (item.Bool() != prior) {
                    item.setConfigBool(prior);
                }
            }
        } else {
            // No captured active state (Ghost was never active in a tracked session);
            // fall back to enabling Ghost so a cancelled flush still keeps messages held.
            NekoConfig.setGhostMode(true);
        }
        // Re-seed the baseline so the next genuine ghost-off edge still fires.
        prefs().edit().putBoolean(KEY_LAST_GHOST_ACTIVE, NekoConfig.isGhostModeActive()).apply();
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private static void performFlush(ArrayList<HeldItem> items,
                                     java.util.HashMap<Integer, Integer> epochByAccount,
                                     java.util.HashMap<Integer, Integer> sessionByAccount) {
        if (items.isEmpty()) {
            flushInProgress = false;
            return;
        }
        flushInProgress = true;
        // Stash this flush's pinned per-account session tokens for the terminal report.
        flushSessionByAccount = sessionByAccount;
        // Reset the honest sent-counter for this flush. Only a proven handoff
        // increments it, so the completion bulletin reports transmitted messages, not
        // merely queued ones.
        flushSent.set(0);
        // Every item is processed (stale orphans get cleaned), but only genuinely
        // pending sends are reported to the user, so the counts stay honest.
        final int all = items.size();
        final int pending = all;
        // Completion is observed, not timed: each item, whatever its fate (sent and
        // cleaned, re-held because Ghost came back on, discarded because the user
        // deleted it mid-stagger, or a stale orphan cleared) decrements this counter,
        // and only the item that brings it to zero releases the flush guard and
        // reports. An elapsed-time completion could fire while a paid-message dialog
        // still waits on the user, and clearing the guard early would let a second
        // ghost-off start a concurrent flush over this one.
        final AtomicInteger remaining = new AtomicInteger(all);
        // The store generation and the synchronous session epoch are pinned per account
        // at dialog-creation time (promptFlush's collect callback), not here. The
        // confirmation is a direct dialog the user can leave up indefinitely, and a
        // logout during that window bumps both values before the positive button fires;
        // capturing them here -- after the tap -- would read the already-bumped values,
        // so the pre-send recheck would compare bumped==bumped and pass, sending the
        // rows collected under the previous session through the reused slot. Pinning at
        // dialog creation makes such a logout a mismatch, so the flush abandons instead.
        for (int i = 0; i < all; i++) {
            HeldItem item = items.get(i);
            final int epoch = epochByAccount.get(item.account);
            final int session = sessionByAccount.get(item.account);
            AndroidUtilities.runOnUIThread(() -> flushItem(item, remaining, pending, epoch, session), i * FLUSH_STAGGER_MS);
        }
    }

    private static void onItemTerminal(AtomicInteger remaining, int pending) {
        if (remaining.decrementAndGet() > 0) {
            return;
        }
        // Runs on the UI thread (every caller path posts here), so this write to
        // flushInProgress is serialised with promptFlush's claim.
        flushInProgress = false;
        if (pending <= 0) {
            return;
        }
        // Snapshot the sent count now, on the UI thread, before the async countHeld hop
        // below. flushInProgress was just cleared, so a second flush may start, reset
        // flushSent and repopulate it while our count is in flight; reading flushSent
        // inside the callback would then report the later flush's number on this
        // flush's bulletin. Capturing it here pins it to this flush.
        final int sent = flushSent.get();
        // Validate this flush's pinned per-account sessions synchronously here, on the
        // UI thread logout bumps sessionEpoch on, before the async countHeld hop and
        // the bulletin. The completion report reads the global flushSent and displays
        // against the current LaunchActivity fragment; if a logout reused any
        // participating account's slot mid-flush, that fragment now belongs to a
        // different session and must not receive this flush's result. A moved epoch on
        // any of them means abandon the report -- the per-item sends already ran under
        // their own guards, this only suppresses the stale UI publish.
        final java.util.Map<Integer, Integer> pinned = flushSessionByAccount;
        if (pinned != null) {
            for (java.util.Map.Entry<Integer, Integer> e : pinned.entrySet()) {
                if (sessionEpoch.get(e.getKey()) != e.getValue()) {
                    return;
                }
            }
        }
        countHeld(stillHeld -> {
            // Re-validate the pinned sessions here too, not only before the countHeld
            // hop above. countHeld does a per-account storage round-trip, and a
            // logout/relogin during it advances sessionEpoch; without this second check
            // the callback would still combine this flush's sent count with the new
            // owner's held count and publish it against the new session's fragment. The
            // countHeld callback is delivered on the UI thread (collectHeld posts it via
            // runOnUIThread), the same thread logout bumps sessionEpoch on, so this
            // compare is race-free -- the same guard postScheduledCount applies after
            // its own async hop.
            if (pinned != null) {
                for (java.util.Map.Entry<Integer, Integer> e : pinned.entrySet()) {
                    if (sessionEpoch.get(e.getKey()) != e.getValue()) {
                        return;
                    }
                }
            }
            // Report even when nothing was sent, as long as rows remain held. A held
            // row the flush declined to send -- its dialog became paid, or Ghost came
            // back on -- stays held and must be reported as not sent, never silently
            // omitted: the user toggled Ghost off expecting a drain and is owed the
            // count that did not go. Stay silent only when nothing was sent AND nothing
            // remains held (e.g. the only row was deleted mid-flush), where there is
            // genuinely nothing to report.
            if (sent <= 0 && stillHeld <= 0) {
                return;
            }
            BaseFragment f = LaunchActivity.getLastFragment();
            if (f == null || f.getParentActivity() == null) {
                return;
            }
            // Report what actually happened, not what was queued. flushSent
            // counts only proven, uncancelled handoffs; a row the user deleted mid-flush
            // or one re-held because Ghost returned never increments it. So a flush whose
            // only row was deleted reports nothing instead of the old "1 held message
            // sent". The denominator is sent + stillHeld -- the rows that still existed
            // and were candidates -- so deleted rows never inflate it.
            CharSequence text;
            if (stillHeld <= 0) {
                text = LocaleController.formatPluralString("GhostHoldFlushed", sent);
            } else {
                text = LocaleController.formatString(R.string.GhostHoldFlushedPartial, sent, sent + stillHeld);
            }
            BulletinFactory.of(f).createSimpleBulletin(R.raw.chats_infotip, text).show();
        });
    }

    private static void flushItem(HeldItem item, AtomicInteger remaining, int pending, int epoch, int session) {
        // Re-check Ghost per item on the privacy invariant, which is about Ghost
        // alone, not the hold preference: if Ghost came back on mid-flush this
        // message must stay held even when Hold Messages was turned off in the same
        // window (isHoldActive() would be false then and wrongly let it leak).
        if (NekoConfig.isGhostModeActive()) {
            onItemTerminal(remaining, pending);
            return;
        }
        final GhostHoldStore store = GhostHoldStore.getInstance(item.account);
        // Never act on the snapshot captured at collect time. During the stagger the
        // user can delete a held row (the messagesDeleted observer removes the fork
        // record); re-read the current record on the store's serial queue immediately
        // before dispatch. A gone record means discard. Mark the record FLUSHING here,
        // durably, so a kill after handoff begins is reconciled at next start rather
        // than silently re-driven or lost.
        store.runOwned(epoch, () -> {
            final GhostHoldStore.HeldRecord rec = store.selectOnQueue(item.mid);
            HeldItem fresh = null;
            // Only proceed to dispatch once the FLUSHING claim has durably persisted.
            // If the UPDATE fails, leave the row HELD and skip it this cycle: it
            // re-drives on the next flush. Dispatching on a failed claim would let a
            // kill after the stock write but before fork deletion strand the row as
            // HELD, which reconcile (FLUSHING-only) would then re-send -- a duplicate.
            if (rec != null && store.updateStateOnQueue(item.mid, GhostHoldStore.STATE_FLUSHING)) {
                fresh = toHeldItem(item.account, rec);
                if (fresh == null) {
                    // Row was durably marked FLUSHING but its stored blob will not
                    // decode. Left as-is it is hidden (HELD-only render/collect) and
                    // never retried until a restart reconcile, while the flush counts
                    // it terminal -- an unsent message that silently vanishes. Put it
                    // back to HELD so it renders and re-drives, and refresh the count.
                    // dispatchFreshItem's fresh==null branch must stay the pure "record
                    // deleted" case, so this revert belongs here where rec proves the
                    // row still exists.
                    store.updateStateOnQueue(item.mid, GhostHoldStore.STATE_HELD);
                    postScheduledCount(item.account, item.dialogId, session);
                }
            }
            final HeldItem f = fresh;
            AndroidUtilities.runOnUIThread(() -> dispatchFreshItem(item, f, remaining, pending, epoch, session));
        }, () -> AndroidUtilities.runOnUIThread(() -> onItemTerminal(remaining, pending)));
    }

    private static void dispatchFreshItem(HeldItem item, @Nullable HeldItem fresh, AtomicInteger remaining, int pending, int epoch, int session) {
        final int account = item.account;
        final long dialogId = item.dialogId;
        final int mid = item.mid;
        // The record vanished during the stagger (user deleted it): nothing to send,
        // nothing to remove.
        if (fresh == null) {
            onItemTerminal(remaining, pending);
            return;
        }
        // Ghost flipped back on during the re-read hop -> keep it held. Revert the
        // FLUSHING mark so it renders and re-drives cleanly on the next flush.
        if (NekoConfig.isGhostModeActive()) {
            revertToHeld(account, mid, epoch, () -> onItemTerminal(remaining, pending));
            return;
        }

        final TLRPC.Message m = fresh.message;
        int now = ConnectionsManager.getInstance(account).getCurrentTime();
        boolean future = m.date != GHOST_HELD_DATE_SENTINEL && m.date > now;
        int scheduleDate = future ? m.date : 0;

        MessageObject mo = new MessageObject(account, m, false, true);
        mo.scheduled = future;
        // of(MessageObject) rebuilds the send from the stored message: text, the full
        // reply header, silent, invert_media and params (including our marker) all ride
        // along. It forces searchLinks/scheduleDate on, so override both below.
        SendMessagesHelper.SendMessageParams p = SendMessagesHelper.SendMessageParams.of(mo);
        // Entities are the exception: of(mo) leaves p.entities null, and the funnel's
        // outgoing request reads its entities from the params, not from the stored
        // message (SendMessagesHelper:4399,5462). So bold/links/mentions -- which we do
        // persist onto the blob, and which isHoldableTextSend's "Held faithfully" list
        // promises -- would silently vanish on flush, degrading the held message into
        // something other than what was held. Restore them from the stored message so
        // the outgoing request carries them.
        p.entities = m.entities;
        p.scheduleDate = scheduleDate;
        p.searchLinks = m.params == null || !PARAM_VALUE.equals(m.params.get(PARAM_NO_WEBPAGE));
        if (m.params != null) {
            String repeat = m.params.get(PARAM_REPEAT);
            if (repeat != null) {
                try {
                    p.scheduleRepeatPeriod = Integer.parseInt(repeat);
                } catch (NumberFormatException ignore) {
                }
            }
            String effect = m.params.get(PARAM_EFFECT);
            if (effect != null) {
                try {
                    p.effect_id = Long.parseLong(effect);
                } catch (NumberFormatException ignore) {
                }
            }
        }
        // Once handed to the funnel this is a normal outgoing message, so it must not
        // still carry our hold markers -- the funnel writes the stock row from this
        // same message, and a marked stock row is exactly the leak this rebuild
        // removes. The three fields we care about were lifted into first-class fields
        // just above. of(mo) passed messageOwner.params by reference as p.params, so
        // stripping the keys here unmarks the row the funnel writes.
        if (m.params != null) {
            m.params.remove(PARAM_MARKER);
            m.params.remove(PARAM_NO_WEBPAGE);
            m.params.remove(PARAM_REPEAT);
            m.params.remove(PARAM_EFFECT);
            m.params.remove(PARAM_RANDOM);
        }

        // Re-drive in place. The funnel keys its destination table off scheduleDate
        // alone (SendMessagesHelper:5306-5320), not any current table, so a send-now
        // re-drive (scheduleDate == 0) makes the funnel write messages_v2, while a
        // future-dated one writes scheduled_messages_v2 -- both under the reused
        // negative id, which is what completeHandoff probes for.
        // An automated flush never opens a paywall. The dialog was not paid when this
        // row was held (isHoldableTextSend excludes paid dialogs), but it can become
        // paid before the flush. Re-driving it now would make the funnel open the Stars
        // paywall; if Ghost is re-enabled while that paywall is open and the user then
        // accepts, the funnel's deferred callback -- which we do not own -- would
        // transmit under Ghost, breaking the core no-leak invariant. So re-run the same
        // paid check here: if the dialog is now paid, leave the record held rather than
        // re-drive it. It stays visible in Scheduled, the flush bulletin reports it as
        // not sent, and the user can send it by hand at the price they are shown.
        if (isPaidDialog(account, dialogId)) {
            revertToHeld(account, mid, epoch, () -> onItemTerminal(remaining, pending));
            return;
        }
        final boolean fut = future;
        final SendMessagesHelper.SendMessageParams sendParams = p;
        final GhostHoldStore store = GhostHoldStore.getInstance(account);
        // Final revalidation immediately before dispatch. flushItem marked the record
        // FLUSHING and then hopped to the UI thread; during that hop the user can
        // delete the held row (the messagesDeleted observer removes the fork record).
        // Acting on the snapshot alone would send a message the user just deleted, so
        // re-read on the store's serial queue right before the send and discard if the
        // record is gone or no longer FLUSHING. This does not close the window to zero
        // -- the send itself must run on the UI thread one hop later -- but it shrinks
        // it to that single hop, which is the tightest a UI-thread send allows.
        store.runOwned(epoch, () -> {
            GhostHoldStore.HeldRecord still = store.selectOnQueue(mid);
            final boolean valid = still != null && still.state == GhostHoldStore.STATE_FLUSHING;
            AndroidUtilities.runOnUIThread(() -> {
                if (!valid) {
                    onItemTerminal(remaining, pending);
                    return;
                }
                // NagramX: Ghost and paid were checked before this final
                // store-queue hop, but the user can re-enable Ghost -- or the dialog can
                // become paid, or LOG OUT -- during it. The retry object bypasses
                // maybeHold, so dispatching now would transmit under Ghost, open a Stars
                // paywall whose deferred callback we do not own, or send the logged-out
                // user's message through a reused slot. The store generation only moves
                // when the async teardown runs, which can lag; the session epoch moved
                // synchronously the instant logout began. Both this recheck and the
                // logout bump run on the UI thread, so they are strictly ordered with no
                // race -- do not move either off it. Keep the message held (or abandon it
                // on logout) if any is true.
                if (sessionEpoch.get(account) != session) {
                    // Logout has begun. Treat exactly like store invalidation: abandon
                    // the send. The teardown will purge the row; nothing is transmitted.
                    onItemTerminal(remaining, pending);
                    return;
                }
                if (NekoConfig.isGhostModeActive() || isPaidDialog(account, dialogId)) {
                    revertToHeld(account, mid, epoch, () -> onItemTerminal(remaining, pending));
                    return;
                }
                SendMessagesHelper.getInstance(account).sendMessage(sendParams);
                // Delete-on-write completion: the fork record is removed only once the
                // funnel has provably written its stock row for this negative id.
                // completeHandoff proves that on the storage queue (enqueued after the
                // funnel's own putMessages(useQueue=true)) and signals this item
                // terminal only after it has run. If the funnel wrote nothing (early
                // return / became paid), the record is reverted to HELD for the next
                // flush; nothing is ever lost. mo is passed so a send-now twin can be
                // cancelled if the user deleted the held object during the handoff.
                completeHandoff(account, mid, dialogId, fut, epoch, session, mo, () -> onItemTerminal(remaining, pending));
            });
        }, () -> AndroidUtilities.runOnUIThread(() -> onItemTerminal(remaining, pending)));
    }
    /**
     * Delete-on-write completion. After the flush hands a held message back to the
     * send funnel, the fork record is removed only once the funnel has provably
     * written its stock row under the reused negative id. Runs on the storage queue,
     * enqueued after the funnel's own {@code putMessages(useQueue=true)}, so by the
     * time it runs a completed send-now has written {@code messages_v2}, a completed
     * future-dated send has written {@code scheduled_messages_v2}, and an early
     * return (became paid, {@code sendToUser == null}) has written nothing.
     *
     * <p>Present ⇒ stock now owns delivery and cross-restart retry for this row
     * exactly as {@code getUnsentMessages} does for any unsent message, so the fork
     * record is deleted and P2 is preserved by the stock row, not by us. Absent ⇒ the
     * funnel wrote nothing, so the record is reverted to HELD and re-driven on the
     * next flush. The only ambiguity is a kill after the server confirmed and remapped
     * {@code -N → +P} but before this delete: at the next start's reconcile the row is
     * absent-by-{@code -N} and is re-driven, producing a duplicate. Per the design that
     * is the correct direction to fail -- duplicate, never loss.
     */
    private static void completeHandoff(int account, int mid, long dialogId, boolean future, int epoch, int session, @Nullable MessageObject sentObj, @Nullable Runnable onDone) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            boolean handedOff = false;
            SQLiteCursor probe = null;
            try {
                SQLiteDatabase db = storage.getDatabase();
                String table = future ? "scheduled_messages_v2" : "messages_v2";
                probe = db.queryFinalized("SELECT 1 FROM " + table + " WHERE mid = " + mid + " AND uid = " + dialogId + " LIMIT 1");
                handedOff = probe.next();
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (probe != null) {
                    probe.dispose();
                }
            }
            final boolean ho = handedOff;
            GhostHoldStore store = GhostHoldStore.getInstance(account);
            store.runOwned(epoch, () -> {
                if (ho) {
                    // NagramX: the funnel wrote the stock twin, but check the
                    // fork row is still present before completing. If it is GONE, the
                    // user deleted the held object during the handoff window and the
                    // messagesDeleted observer already removed the fork row -- both it
                    // and this check run on the store's serial queue, so a deletion that
                    // reached the queue first is seen here. The funnel's twin would then
                    // transmit something the user destroyed: a send-now twin (messages_v2)
                    // via the unsent scan, a future-dated twin (scheduled_messages_v2) at
                    // its scheduled date. Cancel it either way. sentObj.scheduled is set
                    // from `future`, so cancelSendingMessage routes the deletion to the
                    // matching table (mode SCHEDULED vs main). This closes the main
                    // window; a deletion that reaches the queue only after this op ran is
                    // bounded by removeStaleScheduledItem, which clears the display object
                    // one UI hop later -- the same one-hop limit a UI-thread send already
                    // documents. Cancellation is best-effort by nature (the network send
                    // may already be gone), which is the correct direction under NOT LOST
                    // > NOT LEAKED > NOT DUPLICATED: a stray duplicate is the least-bad
                    // outcome.
                    boolean rowGone;
                    try {
                        rowGone = !store.isPresentOnQueue(mid);
                    } catch (Exception e) {
                        // A transient fork-store read failure is not a proven deletion.
                        // selectOnQueue would fold that error into a null (fail closed,
                        // correct for the render and flush readers), but here "gone"
                        // triggers cancelSendingMessage on a twin that was already handed
                        // off -- so a read error would cancel a live send and lose the
                        // message. NOT LOST outranks the stray-duplicate risk, so treat a
                        // read failure as "still present": complete the handoff and let
                        // reconciliation clean up rather than cancel on a false negative.
                        FileLog.e(e);
                        rowGone = false;
                    }
                    if (rowGone && sentObj != null) {
                        AndroidUtilities.runOnUIThread(() -> {
                            // NagramX: the runOwned gate above is the async store
                            // generation, which only moves once logout's queued teardown
                            // runs. The flush's captured session moved synchronously the
                            // instant logout began, on this same UI thread. Revalidate it
                            // immediately before acting: if logout landed during the storage
                            // round trip the account slot may already be a different session,
                            // and cancelSendingMessage would act on the new account with the
                            // old message. A stale callback must do nothing, not act on the
                            // wrong account.
                            if (sessionEpoch.get(account) != session) {
                                return;
                            }
                            SendMessagesHelper.getInstance(account).cancelSendingMessage(sentObj);
                        });
                    } else {
                        store.deleteOnQueue(mid);
                        // Only a genuine, uncancelled handoff counts as sent.
                        flushSent.incrementAndGet();
                        if (!future) {
                            // NagramX: a send-now handoff wrote the messages_v2 twin and
                            // removed the fork row, but an already-open Scheduled list still
                            // holds the display-only held object -- the HELD-only render
                            // filter only governs future loads. Drop it from that open list
                            // by mid so it can't be deleted there as held, which would clear
                            // an empty scheduled_messages_v2 while stranding the messages_v2
                            // twin for the unsent scan. Pure UI dispatch, no stock write;
                            // scheduled-scoped so the main-view twin's fragment ignores it.
                            AndroidUtilities.runOnUIThread(() -> {
                                // NagramX: same UI-thread revalidation as the cancel
                                // hop. This posts a scheduled messagesDeleted through the
                                // account's NotificationCenter, so a logout that reused the
                                // slot mid-handoff must abort it rather than reach the new
                                // session's Scheduled list.
                                if (sessionEpoch.get(account) != session) {
                                    return;
                                }
                                removeStaleScheduledItem(account, dialogId, mid);
                            });
                        }
                    }
                } else {
                    store.updateStateOnQueue(mid, GhostHoldStore.STATE_HELD);
                }
                postScheduledCount(account, dialogId, session);
                // Signal the item terminal only after this resolution has run, whatever
                // its outcome, and always on the UI thread (flushInProgress lives there).
                // This is what makes flush completion observed, not timed.
                if (onDone != null) {
                    AndroidUtilities.runOnUIThread(onDone);
                }
            }, () -> {
                // The store was torn down (logout) during the storage-queue round trip.
                // A handed-off row is already gone with the deleted db and stock still
                // owns any sent message; a not-handed-off row was a re-hold the logout
                // has intentionally discarded. Either way there is nothing left to
                // resolve -- just release the flush so it can complete.
                if (onDone != null) {
                    AndroidUtilities.runOnUIThread(onDone);
                }
            });
        });
    }

    /**
     * Removes a handed-off send-now message from any already-open Scheduled list by
     * posting a scheduled-scoped {@code messagesDeleted} for its negative id. The
     * render-time HELD-only filter only affects future loads, so without this an open
     * list keeps the stale display-only held object after the fork row is deleted; a
     * user deleting it there would clear scheduled_messages_v2 (empty for a send-now)
     * while leaving the messages_v2 twin for the unsent scan to transmit. This is a
     * pure NotificationCenter dispatch -- no stock read or write -- and the scheduled
     * flag scopes the UI reaction to Scheduled fragments, so the main-view twin is
     * untouched. channelId is -dialogId for a channel/supergroup and 0 otherwise,
     * matching ChatActivity.processDeletedMessages' channel gate.
     */
    private static void removeStaleScheduledItem(int account, long dialogId, int mid) {
        long channelId = 0;
        if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            if (ChatObject.isChannel(chat)) {
                channelId = -dialogId;
            }
        }
        ArrayList<Integer> ids = new ArrayList<>(1);
        ids.add(mid);
        NotificationCenter.getInstance(account).postNotificationName(
                NotificationCenter.messagesDeleted, ids, channelId, true, false, false, 0);
    }

    /**
     * Reverts a record's FLUSHING mark back to HELD when the flush declined to send
     * it (Ghost came back on, or the dialog became paid), then runs {@code onDone} on
     * the UI thread. The record stays visible in Scheduled and re-drives cleanly on
     * the next flush.
     */
    private static void revertToHeld(int account, int mid, int epoch, @Nullable Runnable onDone) {
        GhostHoldStore store = GhostHoldStore.getInstance(account);
        Runnable finish = () -> {
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        };
        store.runOwned(epoch, () -> {
            store.updateStateOnQueue(mid, GhostHoldStore.STATE_HELD);
            finish.run();
        }, finish);
    }

    // ---- held-queue reads ----

    /**
     * Async count of held messages for the settings screen; delivered on the UI thread.
     */
    public static void countHeld(Utilities.Callback<Integer> onDone) {
        collectHeld(items -> onDone.run(items.size()));
    }

    private static void collectHeld(Utilities.Callback<ArrayList<HeldItem>> onDone) {
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accounts.add(a);
            }
        }
        if (accounts.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> onDone.run(new ArrayList<>()));
            return;
        }
        // NagramX: pin each account's session epoch at the collection root, on the UI
        // thread, before the async store-queue hop. runOwned pins the store generation
        // so a stale row is never read, but the session epoch was previously sampled
        // only downstream (promptFlush, at dialog creation) after this collection had
        // already returned -- so a logout that raced the collection window let the
        // aggregate publish the previous session's snapshot while the downstream
        // capture read the new session's token, and the two matched. Re-checking here,
        // at the single point every held-row collection funnels through, closes that
        // window for both consumers (the flush confirmation and the settings count) at
        // once, instead of adding a fourth per-consumer guard. Logout bumps sessionEpoch
        // on the UI thread, and this capture and the completion re-check below both run
        // on it, so the comparison is serialised against logout. Fail toward publishing
        // nothing: a mismatch delivers an empty result, so the flush finds no rows and
        // every held message stays held under whoever now owns the slot.
        final int[] sessionAtCollect = new int[UserConfig.MAX_ACCOUNT_COUNT];
        for (int account : accounts) {
            sessionAtCollect[account] = sessionEpoch.get(account);
        }
        final List<HeldItem> result = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger remaining = new AtomicInteger(accounts.size());
        for (int account : accounts) {
            final GhostHoldStore store = GhostHoldStore.getInstance(account);
            Runnable done = () -> {
                if (remaining.decrementAndGet() == 0) {
                    AndroidUtilities.runOnUIThread(() -> {
                        for (int a : accounts) {
                            if (sessionEpoch.get(a) != sessionAtCollect[a]) {
                                onDone.run(new ArrayList<>());
                                return;
                            }
                        }
                        onDone.run(new ArrayList<>(result));
                    });
                }
            };
            store.runOwned(store.currentGeneration(), () -> {
                // NagramX: HELD only, never FLUSHING. This collection feeds the flush,
                // so a FLUSHING row here would be dispatched again -- and a row stuck
                // FLUSHING (its handoff already wrote the stock twin but the follow-up
                // delete failed) would send a duplicate. FLUSHING rows are resolved
                // solely by startup reconciliation (present twin -> delete, absent ->
                // re-hold), not by the flush. The settings count reads the same set,
                // where HELD-only is also correct: a handed-off row is being sent, not
                // held.
                for (GhostHoldStore.HeldRecord rec : store.selectByStateOnQueue(GhostHoldStore.STATE_HELD)) {
                    HeldItem it = toHeldItem(account, rec);
                    if (it != null) {
                        result.add(it);
                    }
                }
                done.run();
            }, done);
        }
    }

    // ---- render injection (read-time, display-only) ----

    /**
     * Injects this account's held messages for {@code dialogId} into a freshly loaded
     * scheduled-message list as display-only objects. Called at the single read-time
     * chokepoint in {@code MessagesController.processLoadedMessages}, after every cache
     * write has already happened and only into the UI-bound {@code objects} list, so a
     * held message is rendered but never written back to any stock table -- the property
     * that keeps the no-leak invariant structural rather than guarded. Each object is
     * built from a fresh decode of the stored blob, so nothing the UI does to a shown
     * message can reach the cache. Runs on the message-load thread and reads only the
     * store's lock-free published snapshot.
     */
    public static void injectHeldScheduled(int account, long dialogId, ArrayList<MessageObject> objects) {
        if (objects == null) {
            return;
        }
        // Re-arm after an in-process re-login (see maybeHold): the render path is
        // reached post-login when the user opens a scheduled list, so it re-registers
        // the observers if checkOnProcessStart did not run again.
        initAccount(account);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        GhostHoldStore store = GhostHoldStore.getInstance(account);
        boolean naxOwns = store.ownsUser(selfId);
        // The published snapshot is read off-queue for speed, so it can momentarily
        // still hold a previous slot owner's rows in the window between a re-login and
        // the queue re-opening the file under the new owner. ownsUser refuses that
        // window: it returns true only once an activated open has stamped this user,
        // so a stale snapshot is never rendered. Fails toward showing nothing until
        // the store is confirmed, never toward showing another user's held messages.
        if (!naxOwns) {
            return;
        }
        List<GhostHoldStore.HeldRecord> records = store.cachedForDialog(dialogId);
        if (records == null || records.isEmpty()) {
            return;
        }
        java.util.HashSet<Integer> present = new java.util.HashSet<>();
        for (int i = 0; i < objects.size(); i++) {
            present.add(objects.get(i).getId());
        }
        // NagramX: LOAD adapter for the held-row ordering. See THE ORDERING CONTRACT on
        // HeldOrderView below for the full rule; in short, held rows that share one exact date
        // tie under the stock scheduled sort (negative local ids skip its id tie-break), and a
        // higher rank (newer hold) must land at a lower array index because the list is
        // reverse-stacked. On this path there is nothing to compute here: the pre-sort `objects`
        // list is about to be sorted by the fork-overridden scheduled comparator
        // (MessagesController.java:12401-12415), which resolves a same-date tie that involves a
        // held row by HeldOrderView rank. This loop appends the whole snapshot NEWEST-FIRST
        // (iterate records in reverse -- descending rank); that append order already lands every
        // tying-date run oldest-at-top even under a plain stable sort, but the override no longer
        // leaves it to that accident -- it orders the run by rank explicitly, so an
        // already-present held member re-shown on reload is ranked too. A row with a genuinely
        // distinct date is re-sorted to its date position regardless of append order, so
        // reversing it here is inert. The live path (placeLiveHeldRow) reproduces the identical
        // ordering for a single row arriving into the already-sorted rendered list.
        for (int idx = records.size() - 1; idx >= 0; idx--) {
            GhostHoldStore.HeldRecord rec = records.get(idx);
            // Inject only HELD rows. A FLUSHING row has been handed to the send
            // funnel: once the funnel writes its stock row the message is an ordinary
            // sending message and already renders in the timeline, so continuing to
            // show it here would be a stale second copy -- and, worse, would let the
            // user delete a handed-off message through the scheduled path, which only
            // clears scheduled_messages_v2 and would strand the messages_v2 twin for
            // the unsent scan to auto-send. Not rendering it means it cannot be
            // deleted through the held path, so that race cannot arise. A FLUSHING row
            // that the funnel did not actually write is reverted to HELD promptly
            // (revertToHeld / completeHandoff / startup reconcile), so it reappears.
            if (rec.state != GhostHoldStore.STATE_HELD) {
                continue;
            }
            if (present.contains(rec.mid)) {
                // A stock scheduled row for this mid is still present (a migration delete
                // not yet applied): show it once, from the stock copy, not twice.
                continue;
            }
            TLRPC.Message m = GhostHoldStore.decode(rec.data, selfId);
            if (m == null) {
                continue;
            }
            m.id = rec.mid;
            m.dialog_id = rec.dialogId;
            m.date = rec.date;
            // NagramX: send_state is client-only and never part of the serialized blob,
            // so a decoded row comes back as NONE, not the SENDING a fresh hold carries
            // (see the sentinel-build path). Stock restores send_state from its own
            // column the same way (MessagesStorage scheduled read). Without this a
            // reloaded held row reads as not-sending: getMessageType classifies it
            // invalid and the single-row cancel/delete affordance is never populated,
            // leaving it un-actionable until flush. Membership stays governed by
            // STATE_HELD; this only restores presentation.
            m.send_state = MessageObject.MESSAGE_SEND_STATE_SENDING;
            MessageObject mo = new MessageObject(account, m, true, true);
            mo.scheduled = true;
            objects.add(mo);
            present.add(rec.mid);
        }
    }

    // ==== Held-row ordering: one oracle, two adapters ================================
    //
    // THE ORDERING CONTRACT (the single source of truth for both the load and the live
    // path; if you change it, change it here and nowhere else).
    //
    // A held row's RANK is its position in this dialog's flush snapshot -- cachedForDialog(),
    // which is oldest-first (master insertion order live, ORDER BY mid DESC cold), i.e. the
    // exact order the flush sends in. Oldest hold == rank 0. One caveat on "oldest-first":
    // legacy migration (migrateAccount) seeds master in the order it scans the old stock tables,
    // which is not intrinsically oldest-first, so it sorts the merged batch mid DESC before
    // inserting. That explicit sort -- not an accident of scan order -- is what makes the claim
    // hold for a migrated backlog; ordinary live holds and any post-restart cold load are
    // oldest-first without it.
    //
    // The Scheduled list is reverse-stacked (message array index 0 renders at the SCREEN BOTTOM),
    // so to read a held run oldest-at-top the mapping is: a HIGHER rank (newer hold) sorts to a
    // LOWER array index. The oldest hold ends up at the top of its run, the newest at the bottom,
    // which is send order top-to-bottom.
    //
    // Held rows tie under the UPSTREAM scheduled comparator (date DESC, breaking a date tie
    // with id only when BOTH ids are >= 0): held rows carry negative local ids, so ANY group
    // of held rows that share one exact date collapses to a 0-tie. That is not limited to one
    // sentinel: it covers the plain hold date (GHOST_HELD_DATE_SENTINEL, 0x7FFFFFFD), the
    // send-when-online date (0x7FFFFFFE, stored verbatim by persistHeld -- also a shared
    // literal, not a real timestamp), and two timed holds that land in the same wall-clock
    // second. The fork replaces that comparator with an override
    // (MessagesController.java:12401-12415) that resolves a held-involved tie by rank; rank
    // ordering is applied per tying-date GROUP. A row with a genuinely DISTINCT date is placed
    // by the stock date sort (the override defers to it, id >= 0 guard included, for any pair
    // with no held row) and is never touched here. The override still leans on the
    // stock comparator's shape for non-held pairs, so an upstream bump to it must be re-checked
    // against this override -- see docs/codemap/upstream-traps.md.
    //
    // Two adapters, one contract, because the two paths act on different substrates:
    //   - LOAD (injectHeldScheduled, above): appends the whole snapshot to the pre-sort
    //     `objects` list newest-first, then lets the overridden comparator settle the ties by
    //     rank. That append order IS descending rank, so every tying-date run comes out
    //     oldest-at-top even under a plain stable sort; the override makes it explicit.
    //   - LIVE (placeLiveHeldRow, below): a single row arriving into the already-ordered
    //     rendered list; there is no re-sort to lean on, so it computes the row's insertion
    //     index directly from its rank relative to the rows already on screen.

    /**
     * Immutable, per-operation ordering view over one dialog's held rows. It exposes the
     * ordering RELATION only -- a held row's rank in the flush snapshot -- and retains no
     * reference to any UI object, list, view, context or observer, so a caller may build it,
     * use it for the length of one placement operation and drop it (it retains no reference to
     * any UI object, list, view, context or observer across calls).
     *
     * A row's {@code mid} is used ONLY to look its rank up; the numeric value of the mid never
     * takes part in a comparison (held mids are negative local ids and are not ordered by
     * magnitude). {@code rankOf(mid) >= 0} is the authoritative test of ORDER -- "does this row
     * have a place in the held sequence" -- not of held membership itself; a mid that is not a
     * currently-HELD member returns -1 and is never ordered by held rank, and its id is never
     * compared to fabricate held order. A -1 does not mean "not held", though: a row can carry the
     * held marker yet be unranked (transiently FLUSHING, already-removed but still on an open list,
     * or unmigrated), and the live adapter recognises that marker via {@code isHeld} and treats
     * such a row as an older sibling -- see placeLiveHeldRow. What each adapter does with a -1 row
     * otherwise differs -- neither is "leave every -1 row untouched": the live adapter returns
     * stock placement for the ARRIVING row (it is not ours), while the load comparator treats a -1
     * row as the genuine (non-held) side of a pair, so a held sibling (rank >= 0) still sorts above
     * it, but it never reorders two -1 rows by held rank. The distinctness this rests on -- a
     * genuine row's negative local id never equals a held mid -- holds because both are drawn from
     * one per-account counter handed out once each and then
     * decremented ({@code UserConfig.getNewMessageId}, UserConfig.java:130-135, over the
     * persisted {@code lastSendMessageId}); see docs/codemap/upstream-traps.md.
     */
    public static final class HeldOrderView {
        private final java.util.HashMap<Integer, Integer> rankByMid;

        private HeldOrderView(java.util.HashMap<Integer, Integer> rankByMid) {
            this.rankByMid = rankByMid;
        }

        /** Flush-order rank of {@code mid} (0 == oldest held), or -1 if it is not a held member. */
        public int rankOf(int mid) {
            Integer r = rankByMid.get(mid);
            return r == null ? -1 : r;
        }
    }

    /**
     * Builds the {@link HeldOrderView} for {@code dialogId} from this account's published
     * snapshot, applying the SAME ownsUser gate as the load injection so a reused account slot
     * can never order against a stale snapshot (a re-login can leave the off-queue snapshot
     * briefly holding a previous owner's rows). Returns an empty view -- never null -- when the
     * store is not yet owned or the dialog has no held rows, so callers need no null check.
     * Only STATE_HELD rows get a rank, matching exactly what injectHeldScheduled renders.
     */
    public static HeldOrderView heldOrderView(int account, long dialogId) {
        java.util.HashMap<Integer, Integer> rankByMid = new java.util.HashMap<>();
        initAccount(account);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        GhostHoldStore store = GhostHoldStore.getInstance(account);
        if (!store.ownsUser(selfId)) {
            return new HeldOrderView(rankByMid);
        }
        List<GhostHoldStore.HeldRecord> records = store.cachedForDialog(dialogId);
        if (records != null) {
            int rank = 0;
            for (int i = 0; i < records.size(); i++) {
                GhostHoldStore.HeldRecord rec = records.get(i);
                if (rec.state != GhostHoldStore.STATE_HELD) {
                    continue;
                }
                rankByMid.put(rec.mid, rank);
                rank++;
            }
        }
        return new HeldOrderView(rankByMid);
    }

    /**
     * Live-path adapter. Computes where a live-arriving HELD row belongs in the already
     * ordered {@code messages} list so held rows render in the order a cold reload
     * (injectHeldScheduled + the fork's overridden scheduled sort, not the stock date sort --
     * see MessagesController.java:12399-12415) would produce, and returns that index for the
     * caller to use as its {@code placeToPaste}. It orders held rows only: a genuine same-date
     * row is left in stock placement, so it can sit on the opposite side of the held block from
     * where cold load puts it until the next reload -- the accepted online / timed-bucket
     * residual in docs/codemap/upstream-traps.md.
     *
     * Self-gating: returns {@code stockPlaceToPaste} unchanged unless {@code chatMode} is
     * MODE_SCHEDULED AND {@code obj} is a held member of this dialog's snapshot. The
     * MODE_SCHEDULED check is defence in depth, not the real boundary: the stock scheduled
     * filter at ChatActivity.java:27958 ({@code obj.scheduled != (chatMode == MODE_SCHEDULED)})
     * already drops a held row -- published with {@code scheduled = true}
     * (GhostHoldController.java:664) -- on any non-Scheduled timeline before this helper is
     * reached, so a Saved-messages hold cannot arrive here today even though the guard at
     * ChatActivity.java:24102 lets a mode-1 publish fall through to processNewMessages in
     * MODE_SAVED. The gate exists so a future caller added on a path that bypasses that filter
     * still cannot reorder another timeline. A held row on a genuinely distinct date, and every
     * non-held row, keep stock placement.
     *
     * Ordering follows THE ORDERING CONTRACT above: obj is placed within the run of rows sharing
     * its EXACT date, ordered by rank (higher rank -> lower index), with the day's date header
     * kept above it. A genuine same-date row is left in stock placement -- it is never moved --
     * so on the live path a genuine row that arrived before this hold can sit on the opposite
     * side of the held block until the next reload (the accepted online/timed residual in
     * docs/codemap/upstream-traps.md); the plain bucket has no genuine peer and is exact. The
     * header is matched STRUCTURALLY by dateKey, never by numeric date: the stock live header
     * build stamps an "until online" header with a midnight date (ChatActivity.java:28210-28227)
     * while the load build stamps the same header 0x7FFFFFFE (ChatActivity.java:23241-23262);
     * the two dates differ but the day-granular dateKey is identical -- see
     * docs/codemap/upstream-traps.md.
     *
     * Placement is a function of rank and the rows currently on screen, not of arrival order, so
     * processing a batch of holds in any order converges to the same list.
     */
    public static int placeLiveHeldRow(int account, int chatMode, long dialogId,
                                       ArrayList<MessageObject> messages, MessageObject obj, int stockPlaceToPaste) {
        if (chatMode != org.telegram.ui.ChatActivity.MODE_SCHEDULED
                || messages == null || obj == null || obj.messageOwner == null) {
            return stockPlaceToPaste;
        }
        // Cheap membership pre-gate. Only a held row can be reordered, and isHeld reads the
        // fork's param marker (not the date), so it recognises every held bucket -- plain,
        // online and timed alike -- without allocating anything. Skipping the view build for
        // the far more common ordinary scheduled row stops a live batch of non-held arrivals
        // each building a rank map on the UI thread. rankOf below stays the authoritative
        // membership test; this only avoids the work when obj plainly is not ours.
        if (!isHeld(obj)) {
            return stockPlaceToPaste;
        }
        // Built fresh per call by design: the helper retains no UI state (it never holds the
        // messages list, a view or a context across calls), and one dialog's held snapshot is
        // small -- a handful of manual holds -- so rebuilding the rank map per arrival is
        // cheaper than the invalidation a shared cache would need.
        HeldOrderView view = heldOrderView(account, dialogId);
        final int rank = view.rankOf(obj.getId());
        if (rank < 0) {
            // Not a held member of this dialog's snapshot -> not ours; leave stock placement.
            // Fail closed: never fall back to comparing ids for a missing member.
            return stockPlaceToPaste;
        }
        final int exactDate = obj.messageOwner.date;
        final String dateKey = obj.dateKey;

        // Walk the rendered list once. Within obj's EXACT-date run, a held sibling that is OLDER
        // (lower rank) must stay ABOVE obj (higher index); a held sibling that is NEWER (higher
        // rank) and any genuine same-date row must stay BELOW obj (lower index). We do NOT assume
        // the run is contiguous or that the list is exactly in cold-load order: the accepted
        // online/timed residual can leave a genuine same-date row on the far side of the held
        // block until reload. So rather than trust position we scan the whole list and keep two
        // anchors -- the lowest index that must stay above obj (firstAbove) and the highest that
        // must stay below (lastBelow) -- then insert at that boundary. obj lands adjacent to its
        // held siblings; a stray genuine row that is already out of place is left where it is.
        int headerIndex = -1;
        int firstAbove = -1; // lowest index of a same-date row that must stay ABOVE obj
        int lastBelow = -1;  // highest index of a same-date row that must stay BELOW obj
        for (int i = 0; i < messages.size(); i++) {
            MessageObject mm = messages.get(i);
            if (mm == null || mm.messageOwner == null) {
                continue;
            }
            // Only a TRUE day header is a boundary. A video-conversion "processing" row also
            // carries isDateObject (ChatActivity.java:23384) and, for a send-when-online video,
            // the same 0x7FFFFFFE date and dateKey as an online-bucket held row -- but it is a
            // content-side marker, not a boundary. In this list isVideoConversionObject is the
            // only non-header isDateObject producer (the load and live day headers are the rest
            // -- enumerated, not assumed), so excluding it is the complete rule. Left as a
            // header it would both hide from the below-row scan and, because the loop keeps the
            // LAST dateKey match, become the anchor for the header clamp below -- a guard
            // fed a non-boundary index, which can misplace the row worse than no guard at all.
            // Falling through, it has id 0 (rankOf -1) so it counts as a genuine below-row,
            // which is where cold load places it: adjacent to its video, below the held cluster.
            if (mm.isDateObject && !mm.isVideoConversionObject) {
                if (dateKey != null && dateKey.equals(mm.dateKey)) {
                    headerIndex = i;
                }
                continue;
            }
            if (mm.messageOwner.date != exactDate) {
                continue;
            }
            int mmRank = view.rankOf(mm.getId());
            // A held-marked row with no rank is one heldOrderView does not rank (it ranks
            // STATE_HELD members only). That covers three cases, not just one: a row the flush
            // has just marked FLUSHING, an already-removed row a send-now handoff dropped from the
            // store but an open Scheduled list still shows (see the removeStaleScheduledItem hop
            // around :1340), and an unmigrated legacy row. In every case it is OLDER than obj: the
            // flush claims items oldest-first and flushItem returns early once Ghost is re-enabled,
            // so a hold arriving now can only post-date every such row. Treat it as an OLDER
            // sibling, not a genuine row -- classing it as genuine would drop obj below it and
            // reintroduce newest-on-top until reload. (obj itself being rankless is the not-ours
            // case handled above, which still fails closed to stock placement.)
            boolean above = mmRank >= 0 ? mmRank < rank : isHeld(mm);
            if (above) {
                if (firstAbove < 0) {
                    firstAbove = i; // list is ordered, so the first older sibling wins
                }
            } else {
                lastBelow = i; // newer held sibling or a genuine same-date row
            }
        }

        int target;
        if (firstAbove < 0 && lastBelow < 0) {
            // Nothing of obj's exact date is on screen: its date is distinct from every rendered
            // row, so the stock date placement is already correct and cold-load consistent.
            target = stockPlaceToPaste;
        } else if (firstAbove >= 0) {
            target = firstAbove;    // just below the first (lowest-index) older held sibling
        } else {
            target = lastBelow + 1; // above every newer sibling / genuine row of this date
        }
        // A real row never crosses to the far side of its own day header. By the
        // ordering above this already holds, but clamp defensively so a future change can't
        // silently push a held row above its header.
        if (headerIndex >= 0 && target > headerIndex) {
            target = headerIndex;
        }
        if (target < 0) {
            target = stockPlaceToPaste;
        } else if (target > messages.size()) {
            target = messages.size();
        }

        return target;
    }

    /**
     * Decodes a stored record into a flush-ready {@link HeldItem}, restoring random_id
     * from params (it is not part of the serialized blob) so the re-drive reuses the
     * same id instead of the funnel minting a fresh one (random_id == 0 guard at
     * SendMessagesHelper ~:4965). Returns null on a decode failure, leaving the record
     * in place for a later attempt rather than dropping it.
     */
    @Nullable
    private static HeldItem toHeldItem(int account, GhostHoldStore.HeldRecord rec) {
        long selfId = UserConfig.getInstance(account).getClientUserId();
        TLRPC.Message message = GhostHoldStore.decode(rec.data, selfId);
        if (message == null) {
            return null;
        }
        message.id = rec.mid;
        message.dialog_id = rec.dialogId;
        message.date = rec.date;
        if (message.random_id == 0 && message.params != null) {
            String raw = message.params.get(PARAM_RANDOM);
            if (raw != null) {
                try {
                    message.random_id = Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new HeldItem(account, rec.mid, rec.dialogId, message);
    }

    /**
     * Recomputes and posts the absolute scheduled count for a dialog (stock
     * server-scheduled rows + fork-held rows) so the Scheduled button updates when a
     * held message appears or is removed, not just on a fresh chat open.
     * scheduledMessagesUpdated sets the count absolutely, so posting the combined total
     * cannot double-count. The stock count is read on the storage queue; the fork count
     * comes from the store's published snapshot.
     */
    private static void postScheduledCount(int account, long dialogId, int session) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        // NagramX: #ghost-hold. session is the initiating operation's token, captured by
        // the caller when that operation began -- persistHeld's holdSession, the flush's
        // and completeHandoff's session, migration's sessionAtStart, the deletion
        // observer's arrival epoch. It must be passed in, not recaptured here: several
        // callers reach this from a callback that began under an older session, so
        // reading sessionEpoch at this point could sample the NEW session's value and
        // then the UI recheck below would wrongly pass, publishing a count that belongs
        // to the old operation into whoever now owns the reused account slot. sessionEpoch
        // moves synchronously on the UI thread at logout, so comparing the initiating
        // token there is race-free.
        storage.getStorageQueue().postRunnable(() -> {
            int stock = 0;
            SQLiteCursor cursor = null;
            try {
                SQLiteDatabase db = storage.getDatabase();
                cursor = db.queryFinalized("SELECT COUNT(mid) FROM scheduled_messages_v2 WHERE uid = " + dialogId);
                if (cursor.next()) {
                    stock = cursor.intValue(0);
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            int fork = 0;
            GhostHoldStore store = GhostHoldStore.getInstance(account);
            if (store.ownsUser(UserConfig.getInstance(account).getClientUserId())) {
                fork = store.cachedCountForDialog(dialogId);
            }
            final int total = stock + fork;
            AndroidUtilities.runOnUIThread(() -> {
                if (sessionEpoch.get(account) != session) {
                    return;
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.scheduledMessagesUpdated, dialogId, total, true);
            });
        });
    }

    // ---- per-account bring-up ----

    private static final GhostHoldObserver[] observers = new GhostHoldObserver[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] accountInited = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    /**
     * One-time per-account initialisation: register the fork observers (held-row
     * deletion and logout), warm the store's snapshot for the render injection, migrate
     * any legacy held rows out of the stock tables, and reconcile a flush interrupted by
     * a kill. Idempotent per process; the logout observer clears the flag so a re-login
     * re-runs it.
     */
    private static void initAccount(int account) {
        if (accountInited[account]) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            // If logout already ran before this UI turn (appDidLogout posts on the UI
            // thread too, and this runnable may have been queued off-thread by maybeHold
            // just before it), the slot is no longer ours. Claiming it here would warm-
            // load the store and register observers against a logged-out account, and a
            // same-user re-login could then serve the previous session's rows. Skip
            // without claiming; a real login re-triggers init through maybeHold or
            // checkOnProcessStart, both of which run only for an activated account.
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            // Claim the account and install the observer in the same UI turn. If the
            // flag were set before the observer were actually registered, a logout in
            // that gap would be missed (appDidLogout also posts on the UI thread): the
            // DB file would not be deleted and the next login would skip re-init. The
            // outer check is only a cheap off-thread fast path; this one, on the UI
            // thread, is authoritative and also prevents a double install.
            if (accountInited[account]) {
                return;
            }
            accountInited[account] = true;
            NotificationCenter nc = NotificationCenter.getInstance(account);
            GhostHoldObserver obs = new GhostHoldObserver(account);
            observers[account] = obs;
            nc.addObserver(obs, NotificationCenter.messagesDeleted);
            nc.addObserver(obs, NotificationCenter.appDidLogout);
            GhostHoldStore store = GhostHoldStore.getInstance(account);
            store.runOwned(() -> store.selectAllOnQueue());
            migrateAccount(account);
            reconcileFlushing(account);
        });
    }

    /**
     * Moves any legacy held rows -- written by the previous storage design as marked,
     * negative-id, {@code send_state = 1} rows in the stock scheduled_messages_v2 /
     * messages_v2 tables -- into ghost_held. The fork insert happens before the stock
     * delete so there is never a window where the message exists in neither place (P2);
     * a kill in between re-runs idempotently on the next start (mid is the PK, insert
     * REPLACEs). A row whose blob will not decode is left in the stock table (still
     * guarded) and logged rather than dropped.
     */
    private static void migrateAccount(int account) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        final GhostHoldStore store = GhostHoldStore.getInstance(account);
        // Capture the store's generation now; the fork-queue insert below drops the
        // batch if it has changed, i.e. a logout tore the store down after this
        // collection began. Also capture the synchronous session epoch on this (UI)
        // thread: the generation only moves once the async teardown runs on the fork
        // queue, so a logout that has begun but whose teardown has not yet run would
        // still pass the generation gate and insert the old user's stock rows into the
        // reused slot. The epoch moved the instant appDidLogout was seen, so rechecking
        // it before the insert closes that lag.
        final int genAtStart = store.currentGeneration();
        final int sessionAtStart = sessionEpoch.get(account);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<GhostHoldStore.HeldRecord> toInsert = new ArrayList<>();
            ArrayList<Integer> schedDelete = new ArrayList<>();
            ArrayList<Integer> mainDelete = new ArrayList<>();
            java.util.HashSet<Long> dialogs = new java.util.HashSet<>();
            SQLiteDatabase db = storage.getDatabase();
            collectLegacy(db, "scheduled_messages_v2", account, selfId, toInsert, schedDelete, dialogs);
            collectLegacy(db, "messages_v2", account, selfId, toInsert, mainDelete, dialogs);
            // NagramX: collectLegacy scans each stock table with no ORDER BY and appends into one
            // merged list, so the batch is in scan order, not hold order. insertOnQueue appends
            // into master in list order and heldOrderView ranks by that position -- and
            // selectByStateOnQueue flushes in that same order -- so an unsorted batch would seed
            // both the Scheduled render order AND the send order from an accident of the scan.
            // Sort the MERGED list oldest-first (mid DESC, the store's own canonical order; held
            // mids are handed out by a decrementing counter, so a larger / less-negative mid is
            // the older hold) once here, before the insert loop, so a migrated backlog gets a
            // deterministic hold order. Sorting the merged list, not per query, is what gives a
            // mid duplicated across both tables one well-defined position. retainConfirmed pairs
            // the stock delete by set membership, not by position, so reordering here cannot
            // desynchronise the paired delete.
            java.util.Collections.sort(toInsert, (a, b) -> Integer.compare(b.mid, a.mid));
            if (toInsert.isEmpty()) {
                return;
            }
            store.runOwned(genAtStart, () -> {
                // runOwned's generation gate drops this batch if the store was torn
                // down (logout) after it was collected -- a reopen for a different user
                // (a cross-account leak), or the same user's post-logout store the
                // deletion was meant to leave empty (resurrecting destroyed messages).
                // The gate and the teardown's increment both run on this fork queue, so
                // the compare is atomic with the teardown. Dropping is loss-free: the
                // stock rows are deleted only after a confirmed insert, so nothing was
                // removed and the next init re-migrates them.
                //
                // Also drop the batch if the session epoch moved since collection began.
                // The generation gate above only fires once the async teardown has run;
                // this catches the earlier instant logout was seen, before the reused
                // slot's new owner could have its rows inserted here. Both layers are
                // loss-free for the reason above.
                if (sessionEpoch.get(account) != sessionAtStart) {
                    return;
                }
                java.util.HashSet<Integer> insertedOk = new java.util.HashSet<>();
                for (GhostHoldStore.HeldRecord rec : toInsert) {
                    if (store.insertOnQueue(rec)) {
                        insertedOk.add(rec.mid);
                    }
                }
                if (insertedOk.isEmpty()) {
                    return;
                }
                storage.getStorageQueue().postRunnable(() -> {
                    // NagramX: the insert side above is epoch-guarded; guard the paired
                    // stock delete the same way. sessionAtStart is the session epoch
                    // captured when this migration began; if a logout has bumped it since,
                    // the reused account slot may now hold a different session whose own
                    // scheduled rows can carry these same negative mids -- deleting by mid
                    // alone would destroy the new session's message (P2 loss). Skipping is
                    // loss-free for the same reason the insert-side guard is: the legacy
                    // stock rows stay in place and the next init re-migrates them.
                    if (sessionEpoch.get(account) != sessionAtStart) {
                        return;
                    }
                    // Delete a legacy stock row only once its fork insert is confirmed
                    // durable. A row whose insert failed stays in the stock table (still
                    // guarded) so it is retried on the next start rather than deleted
                    // after a lost insert -- deleting an unconfirmed row would lose the
                    // message (P2).
                    ArrayList<Integer> sd = retainConfirmed(schedDelete, insertedOk);
                    ArrayList<Integer> md = retainConfirmed(mainDelete, insertedOk);
                    try {
                        if (!sd.isEmpty()) {
                            db.executeFast("DELETE FROM scheduled_messages_v2 WHERE mid IN(" + join(sd) + ")").stepThis().dispose();
                        }
                        if (!md.isEmpty()) {
                            db.executeFast("DELETE FROM messages_v2 WHERE mid IN(" + join(md) + ")").stepThis().dispose();
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    for (long d : dialogs) {
                        postScheduledCount(account, d, sessionAtStart);
                    }
                });
            });
        });
    }

    private static void collectLegacy(SQLiteDatabase db, String table, int account, long selfId,
                                      ArrayList<GhostHoldStore.HeldRecord> toInsert,
                                      ArrayList<Integer> toDelete, java.util.HashSet<Long> dialogs) {
        SQLiteCursor cursor = null;
        try {
            cursor = db.queryFinalized("SELECT data, mid, uid, date FROM " + table + " WHERE mid < 0 AND send_state = 1");
            while (cursor.next()) {
                byte[] blob = cursor.byteArrayValue(0);
                if (blob == null) {
                    continue;
                }
                TLRPC.Message m = GhostHoldStore.decode(blob, selfId);
                if (m == null) {
                    // Undecodable: leave the stock row intact (the retained guards keep it
                    // from auto-sending) rather than drop the user's message.
                    continue;
                }
                if (!isHeldMessage(m)) {
                    continue;
                }
                int mid = cursor.intValue(1);
                long uid = cursor.longValue(2);
                int date = cursor.intValue(3);
                toInsert.add(new GhostHoldStore.HeldRecord(mid, uid, date, GhostHoldStore.STATE_HELD, blob));
                toDelete.add(mid);
                dialogs.add(uid);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
    }

    /**
     * Reconciles records left in FLUSHING by a kill mid-handoff. For each, probe the
     * stock tables for the reused negative id: present ⇒ the funnel wrote the row and
     * stock now owns delivery, so delete the fork record; absent ⇒ the write never
     * landed, or the send already completed and remapped the id (indistinguishable
     * here), so revert to HELD and let the next flush re-drive it. Failing an absent
     * probe toward re-drive is the deliberate "duplicate, never loss" direction.
     */
    private static void reconcileFlushing(int account) {
        GhostHoldStore store = GhostHoldStore.getInstance(account);
        final int epoch = store.currentGeneration();
        store.runOwned(epoch, () -> {
            ArrayList<GhostHoldStore.HeldRecord> flushing = store.selectByStateOnQueue(GhostHoldStore.STATE_FLUSHING);
            if (flushing.isEmpty()) {
                return;
            }
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                ArrayList<Integer> present = new ArrayList<>();
                ArrayList<Integer> absent = new ArrayList<>();
                SQLiteDatabase db = storage.getDatabase();
                for (GhostHoldStore.HeldRecord rec : flushing) {
                    boolean found = false;
                    SQLiteCursor c = null;
                    try {
                        c = db.queryFinalized("SELECT 1 FROM messages_v2 WHERE mid = " + rec.mid + " AND uid = " + rec.dialogId + " UNION ALL SELECT 1 FROM scheduled_messages_v2 WHERE mid = " + rec.mid + " AND uid = " + rec.dialogId + " LIMIT 1");
                        found = c.next();
                    } catch (Exception e) {
                        FileLog.e(e);
                    } finally {
                        if (c != null) {
                            c.dispose();
                        }
                    }
                    (found ? present : absent).add(rec.mid);
                }
                store.runOwned(epoch, () -> {
                    if (!present.isEmpty()) {
                        store.deleteManyOnQueue(present);
                    }
                    for (int mid : absent) {
                        store.updateStateOnQueue(mid, GhostHoldStore.STATE_HELD);
                    }
                });
            });
        });
    }

    private static String join(ArrayList<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    /** Subset of {@code ids} whose fork insert was confirmed durable. */
    private static ArrayList<Integer> retainConfirmed(ArrayList<Integer> ids, java.util.HashSet<Integer> confirmed) {
        ArrayList<Integer> out = new ArrayList<>();
        for (int mid : ids) {
            if (confirmed.contains(mid)) {
                out.add(mid);
            }
        }
        return out;
    }

    /**
     * Per-account observer for held-row deletion and logout. Deletion: when the user
     * deletes a held row from Scheduled, or deletes a handed-off message's stock twin
     * from the timeline, the notification carries the (negative) id and the matching
     * fork record is removed. Logout: the account's ghost_held database file is deleted
     * so a re-login never inherits held messages.
     */
    private static final class GhostHoldObserver implements NotificationCenter.NotificationCenterDelegate {
        private final int account;

        GhostHoldObserver(int account) {
            this.account = account;
        }

        @Override
        public void didReceivedNotification(int id, int acc, Object... args) {
            if (id == NotificationCenter.appDidLogout) {
                // NagramX: bump the synchronous session epoch FIRST, before the
                // async store teardown is even queued. appDidLogout is delivered on the
                // UI thread and the flush's pre-send recheck reads this on the UI thread,
                // so an in-flight flush that pinned the old value is guaranteed to see
                // the change and abandon its send -- closing the window where the store's
                // own generation has not yet moved (its teardown runs later on the fork
                // queue) and the logged-out user's message could otherwise transmit
                // through the reused account slot.
                sessionEpoch.incrementAndGet(account);
                GhostHoldStore store = GhostHoldStore.getInstance(account);
                store.postTeardown();
                accountInited[account] = false;
                NotificationCenter nc = NotificationCenter.getInstance(account);
                nc.removeObserver(this, NotificationCenter.messagesDeleted);
                nc.removeObserver(this, NotificationCenter.appDidLogout);
                observers[account] = null;
                return;
            }
            if (id == NotificationCenter.messagesDeleted) {
                // NagramX: react to both scheduled-list deletions (a held row the user
                // removed from Scheduled) and ordinary timeline deletions. After a
                // send-now handoff the stock twin lives in the timeline under the reused
                // negative id and is deleted with scheduled == false; ignoring that would
                // leave the fork record FLUSHING while its twin is gone, and reconcile
                // would read the absent row as an interrupted handoff and re-drive it,
                // resurrecting a message the user deleted. selectOnQueue below matches
                // only our own negative ids, so any unrelated deletion is a no-op.
                @SuppressWarnings("unchecked")
                ArrayList<Integer> mids = (ArrayList<Integer>) args[0];
                if (mids == null || mids.isEmpty()) {
                    return;
                }
                ArrayList<Integer> negs = new ArrayList<>();
                for (int mid : mids) {
                    if (mid < 0) {
                        negs.add(mid);
                    }
                }
                if (negs.isEmpty()) {
                    return;
                }
                // NagramX: #ghost-hold. This deletion began now, in the current session;
                // capture that epoch so the count publish below rejects if a logout races
                // the fork-queue hop rather than posting into the reused slot's new owner.
                final int deleteSession = sessionEpoch.get(account);
                GhostHoldStore store = GhostHoldStore.getInstance(account);
                store.runOwned(() -> {
                    java.util.HashSet<Long> dialogs = new java.util.HashSet<>();
                    ArrayList<Integer> toDelete = new ArrayList<>();
                    for (int mid : negs) {
                        GhostHoldStore.HeldRecord rec = store.selectOnQueue(mid);
                        if (rec != null) {
                            dialogs.add(rec.dialogId);
                            toDelete.add(mid);
                        }
                    }
                    if (toDelete.isEmpty()) {
                        return;
                    }
                    store.deleteManyOnQueue(toDelete);
                    for (long d : dialogs) {
                        postScheduledCount(account, d, deleteSession);
                    }
                });
            }
        }
    }

    private static int countDistinctChats(ArrayList<HeldItem> items) {
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (HeldItem item : items) {
            seen.add(item.account + ":" + item.dialogId);
        }
        return seen.size();
    }

    private static final class HeldItem {
        final int account;
        final int mid;
        final long dialogId;
        final TLRPC.Message message;

        HeldItem(int account, int mid, long dialogId, TLRPC.Message message) {
            this.account = account;
            this.mid = mid;
            this.dialogId = dialogId;
            this.message = message;
        }
    }
}
