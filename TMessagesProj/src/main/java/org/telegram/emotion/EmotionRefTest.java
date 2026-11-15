package org.telegram.emotion;

import android.content.SharedPreferences;
import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

/**
 * Debug builds only: tests whether one account can send a sticker using the Telegram reference
 * (document id, access hash, file reference) that another account got when it sent it. If that
 * works, people who saved someone else's pack could send its stickers instantly, with no file on
 * their phone and no upload.
 *
 * Account A sends a sticker anywhere (its Saved Messages is best, since account B must never have
 * seen that message); the reference is recorded. Then, as account B, the debug menu item sends it
 * to B's Saved Messages twice: once with A's file reference and once with an empty one. Telegram's
 * answer to each is posted as a text report in B's Saved Messages.
 */
public final class EmotionRefTest {

    private static final String PREFS_NAME = "emotion_ref_test";

    private EmotionRefTest() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    /** Called when Telegram confirms a sent video; records it if it is one of our stickers. */
    public static void onVideoSent(int account, TLRPC.Document document) {
        if (!BuildVars.DEBUG_VERSION || document == null || EmotionStickers.getHash(document) == null) {
            return;
        }
        try {
            SerializedData data = new SerializedData(document.getObjectSize());
            document.serializeToStream(data);
            String serialized = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP);
            data.cleanup();
            prefs().edit()
                    .putString("document", serialized)
                    .putLong("sender", UserConfig.getInstance(account).getClientUserId())
                    .putLong("time", System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static String getMenuTitle() {
        return BuildVars.DEBUG_VERSION ? "Emotion: send last sticker by another account's reference" : null;
    }

    public static void run(int account) {
        String serialized = prefs().getString("document", null);
        if (serialized == null) {
            toast("No sticker recorded yet. Send a sticker from the other account first.");
            return;
        }
        long sender = prefs().getLong("sender", 0);
        if (sender == UserConfig.getInstance(account).getClientUserId()) {
            toast("The recorded sticker was sent by this account. Switch to the other account and try again.");
            return;
        }
        TLRPC.Document document;
        try {
            SerializedData data = new SerializedData(Base64.decode(serialized, Base64.NO_WRAP));
            document = TLRPC.Document.TLdeserialize(data, data.readInt32(true), true);
            data.cleanup();
        } catch (Exception e) {
            FileLog.e(e);
            toast("Couldn't read the recorded sticker: " + e);
            return;
        }
        long ageMinutes = (System.currentTimeMillis() - prefs().getLong("time", 0)) / 60000;
        StringBuilder report = new StringBuilder("Reference test, sticker ")
                .append(EmotionStickers.getHash(document).substring(0, 8))
                .append("\nrecorded by user ").append(sender).append(", ").append(ageMinutes).append(" min ago")
                .append("\nfile reference: ").append(document.file_reference != null ? document.file_reference.length : 0).append(" bytes");
        toast("Sending twice: with and without the file reference…");
        send(account, document, document.file_reference, result -> {
            report.append("\n1. with A's file reference: ").append(result);
            send(account, document, new byte[0], result2 -> {
                report.append("\n2. with empty file reference: ").append(result2);
                String text = report.toString();
                FileLog.d("EmotionRefTest: " + text.replace('\n', ' '));
                toast(text);
                long selfDialogId = UserConfig.getInstance(account).getClientUserId();
                SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, selfDialogId, null, null, null, false, null, null, null, true, 0, null, false));
            });
        });
    }

    private static void send(int account, TLRPC.Document document, byte[] fileReference, Utilities.Callback<String> done) {
        TLRPC.TL_inputDocument input = new TLRPC.TL_inputDocument();
        input.id = document.id;
        input.access_hash = document.access_hash;
        input.file_reference = fileReference != null ? fileReference : new byte[0];
        TLRPC.TL_inputMediaDocument media = new TLRPC.TL_inputMediaDocument();
        media.id = input;
        TLRPC.TL_messages_sendMedia req = new TLRPC.TL_messages_sendMedia();
        req.peer = new TLRPC.TL_inputPeerSelf();
        req.media = media;
        req.message = "";
        req.random_id = Utilities.random.nextLong();
        long start = System.currentTimeMillis();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            long ms = System.currentTimeMillis() - start;
            if (response instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
            }
            String result = error != null
                    ? "FAILED " + error.code + " " + error.text + " (" + ms + " ms)"
                    : "OK, sent in " + ms + " ms";
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    private static void toast(String text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                android.widget.Toast.makeText(ApplicationLoader.applicationContext, text, android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }
}
