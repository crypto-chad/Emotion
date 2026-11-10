package org.telegram.emotion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;

import java.util.HashMap;

/**
 * Debug builds only: collects what happened to one received sticker (its state, the fetch, when
 * the video appeared) and sends it as one text message to the receiving account's own Saved
 * Messages, so the numbers stay readable, copyable and forwardable on a phone with no logs.
 *
 * All methods run on the UI thread.
 */
final class EmotionDebugReport {

    /** Sent with whatever is known by then, e.g. when the chat was closed and nothing was shown. */
    private static final long SEND_AFTER_MS = 5000;

    private static final HashMap<String, EmotionDebugReport> reports = new HashMap<>();

    private final int account;
    private final String hash;
    private final long arrivalTime = System.currentTimeMillis();
    private String state;
    private String fetch;
    private String shown;
    private boolean sent;

    private EmotionDebugReport(int account, String hash) {
        this.account = account;
        this.hash = hash;
    }

    static void onArrival(MessageObject message, String hash) {
        if (!BuildVars.DEBUG_VERSION) {
            return;
        }
        EmotionDebugReport report = new EmotionDebugReport(message.currentAccount, hash);
        reports.put(hash, report);
        AndroidUtilities.runOnUIThread(report::send, SEND_AFTER_MS);
    }

    static void onState(String hash, String state) {
        EmotionDebugReport report = reports.get(hash);
        if (report != null && report.state == null) {
            report.state = state;
        }
    }

    static void onFetchDone(String hash, String fetch) {
        EmotionDebugReport report = reports.get(hash);
        if (report != null && report.fetch == null) {
            report.fetch = fetch;
            report.sendIfComplete();
        }
    }

    static void onVideoShown(String hash, boolean fromBackend) {
        EmotionDebugReport report = reports.get(hash);
        if (report != null && report.shown == null) {
            report.shown = (System.currentTimeMillis() - report.arrivalTime) + " ms after arrival, from " + (fromBackend ? "backend" : "Telegram");
            report.sendIfComplete();
        }
    }

    private void sendIfComplete() {
        if (fetch != null && shown != null) {
            send();
        }
    }

    private void send() {
        if (sent) {
            return;
        }
        sent = true;
        if (reports.get(hash) == this) {
            reports.remove(hash);
        }
        String text = "Sticker " + hash.substring(0, 8)
                + "\nshown: " + (shown != null ? shown : "not shown (chat not open?)")
                + "\nfetch: " + (fetch != null ? fetch : "not finished")
                + "\nstate: " + (state != null ? state : "not bound");
        FileLog.d("EmotionStickers: " + text.replace('\n', ' '));
        long selfDialogId = UserConfig.getInstance(account).getClientUserId();
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, selfDialogId, null, null, null, false, null, null, null, true, 0, null, false));
    }
}
