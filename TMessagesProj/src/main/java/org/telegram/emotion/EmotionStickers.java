package org.telegram.emotion;

import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes received stickers start playing as fast as possible by fetching a small copy from
 * emotion-backend instead of waiting on Telegram.
 *
 * The sender names the video after the sticker's content hash, so a recipient knows where the
 * file lives before downloading anything. Files are cached by hash, so a sticker seen once is
 * instant from then on, in any chat. The fetch starts when the message arrives, and playback
 * waits briefly for it; if the backend is slow or unreachable the cell falls back to Telegram's
 * copy, which every message still carries for clients without this feature.
 *
 * All state is touched on the UI thread only; downloads run on their own threads.
 */
public final class EmotionStickers {

    private static final String FILE_NAME_PREFIX = "emotion_";
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("^" + FILE_NAME_PREFIX + "([0-9a-f]{64})\\.mp4$");
    private static final String PREFS_NAME = "emotion_stickers";
    private static final String CACHE_DIR_NAME = "emotion_stickers";
    private static final long MAX_CACHE_BYTES = 200L * 1024 * 1024;
    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;

    /** How long a cell waits for the backend before falling back to Telegram's copy. */
    private static final long MAX_PLAYBACK_WAIT_MS = 1500;
    /** After a failure, don't make the next stickers wait for a backend that seems unreachable. */
    private static final long FAILURE_BACKOFF_MS = 60 * 1000;
    private static final int SELF_TEST_DELIVERY_DELAY_S = 20;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    public interface Listener {
        /** The wait for this sticker is over: it is either cached now, or the backend gave up or ran out of time. */
        void onEmotionStickerSettled(String hash);
    }

    private static class Timing {
        long queueMs;
        long responseMs;
        long bodyMs;
        long bytes;
        String edgeCache;
    }

    private static class Fetch {
        final long startTime = System.currentTimeMillis();
        final ArrayList<WeakReference<Listener>> listeners = new ArrayList<>();
        boolean waitOver;
    }

    private static final HashMap<String, Fetch> fetches = new HashMap<>();
    private static final HashMap<String, Long> recentFailures = new HashMap<>();
    private static final ExecutorService downloadExecutor = Executors.newFixedThreadPool(3);
    private static long lastNetworkFailureTime;

    private EmotionStickers() {
    }

    // --- Sender side ---

    public static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** Records that this local file now exists on the backend under this hash. */
    public static void rememberUploaded(String path, String hash) {
        prefs().edit().putString(path, hash).apply();
    }

    /** The hash of a sticker this device uploaded, or null if this file isn't on the backend. */
    public static String getUploadedHash(String path) {
        return TextUtils.isEmpty(path) ? null : prefs().getString(path, null);
    }

    public static TLRPC.TL_documentAttributeFilename createFileNameAttribute(String hash) {
        TLRPC.TL_documentAttributeFilename attribute = new TLRPC.TL_documentAttributeFilename();
        attribute.file_name = FILE_NAME_PREFIX + hash + ".mp4";
        return attribute;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, 0);
    }

    // --- Recipient side ---

    /** Starts fetching stickers in newly arrived messages, so they're ready before the chat is even opened. */
    public static void onMessagesReceived(ArrayList<MessageObject> messages) {
        for (int i = 0, size = messages.size(); i < size; i++) {
            MessageObject message = messages.get(i);
            String hash = getHash(message);
            // A scheduled message hasn't been delivered yet; fetching now would be wasted on a
            // real send, and would make the self-test's file appear to arrive instantly.
            if (hash == null || message.scheduled) {
                continue;
            }
            EmotionDebugReport.onArrival(message, hash);
            if (isSelfTest(message) && !message.isSending() && !message.isSendError()) {
                removeLocalCopies(message, hash);
            }
            if (!isSentFromThisDevice(message)) {
                startFetch(hash);
            }
        }
    }

    /**
     * Points the message at the cached copy if there is one. The chat cell plays a message's
     * cachedQuality, and the player uses attachPath, so neither needs to know about this cache.
     * Files live outside Telegram's own path because its loader deletes any file there whose
     * size differs from the document, and this copy is encoded separately.
     */
    public static void attachCachedFile(MessageObject message) {
        String hash = getHash(message);
        if (hash == null) {
            return;
        }
        File file = getCacheFile(hash);
        if (file == null || !file.exists()) {
            return;
        }
        if (isSentFromThisDevice(message)) {
            return;
        }
        message.messageOwner.attachPath = file.getAbsolutePath();
        message.attachPathExists = true;

        VideoPlayer.VideoUri uri = new VideoPlayer.VideoUri();
        uri.currentAccount = message.currentAccount;
        uri.document = message.getDocument();
        uri.docId = uri.document.id;
        uri.uri = Uri.fromFile(file);
        message.cachedQuality = uri;
    }

    /**
     * Whether a cell should hold off playing this message because the backend's copy may arrive
     * first. When this returns true, the listener is told once the wait is over.
     */
    public static boolean shouldWaitForFile(MessageObject message, Listener listener) {
        String hash = getHash(message);
        if (hash == null || message.scheduled || isSentFromThisDevice(message)) {
            return false;
        }
        attachCachedFile(message);
        File file = getCacheFile(hash);
        if (file == null || file.exists()) {
            return false;
        }
        // Telegram's copy is already on disk, so it can play right now. The self-test keeps waiting,
        // because otherwise it would never exercise the fetch on a phone that sent the sticker.
        if (message.mediaExists && !isSelfTest(message)) {
            return false;
        }
        Fetch fetch = startFetch(hash);
        if (fetch == null || fetch.waitOver) {
            return false;
        }
        fetch.listeners.add(new WeakReference<>(listener));
        return true;
    }

    /**
     * Whether this device already has the video because it sent the message. Being outgoing isn't
     * enough: a sticker sent from the user's other device, or to their Saved Messages from one,
     * is outgoing here too but still has to be downloaded.
     */
    private static boolean isSentFromThisDevice(MessageObject message) {
        if (message.isSending() || message.isSendError()) {
            return true;
        }
        if (isSelfTest(message)) {
            return false;
        }
        String attachPath = message.messageOwner.attachPath;
        if (TextUtils.isEmpty(attachPath) || !new File(attachPath).exists()) {
            return false;
        }
        String hash = getHash(message);
        File cacheFile = hash != null ? getCacheFile(hash) : null;
        return cacheFile == null || !attachPath.equals(cacheFile.getAbsolutePath());
    }

    /**
     * Makes the self-test start from nothing, like a recipient who has never seen this sticker:
     * the phone that sent it would otherwise already hold Telegram's copy and possibly ours.
     * Only app-owned cache files are deleted; the original video the sender picked is left alone.
     */
    private static void removeLocalCopies(MessageObject message, String hash) {
        File cached = getCacheFile(hash);
        if (cached != null) {
            cached.delete();
        }
        FileLoader loader = FileLoader.getInstance(message.currentAccount);
        TLRPC.Document document = message.getDocument();
        loader.getPathToAttach(document, null, false).delete();
        loader.getPathToAttach(document, null, true).delete();
        message.messageOwner.attachPath = "";
        message.attachPathExists = false;
        message.mediaExists = false;
        message.cachedQuality = null;
    }

    /**
     * In debug builds, a sticker sent to Saved Messages is scheduled a few seconds ahead, so the
     * upload happens out of sight and the server then delivers it into the open chat, the way a
     * recipient sees it arrive. Returns 0, meaning send now, everywhere else.
     */
    public static int getSelfTestScheduleDate(int account, long dialogId) {
        if (!BuildVars.DEBUG_VERSION || dialogId != UserConfig.getInstance(account).getClientUserId()) {
            return 0;
        }
        return ConnectionsManager.getInstance(account).getCurrentTime() + SELF_TEST_DELIVERY_DELAY_S;
    }

    /**
     * Lets one phone test the recipient flow: in debug builds, a sticker it sent to its own
     * Saved Messages is treated as received, so it's fetched from the backend and played.
     */
    private static boolean isSelfTest(MessageObject message) {
        return BuildVars.DEBUG_VERSION && message.getDialogId() == UserConfig.getInstance(message.currentAccount).getClientUserId();
    }

    public static String getHash(MessageObject message) {
        if (message == null || message.type != MessageObject.TYPE_VIDEO || !message.isVideoStickerDisplay()) {
            return null;
        }
        return findHash(message.getDocument());
    }

    /** Debug builds note how the cell saw this sticker when first showing it, for the debug report. */
    public static void reportStateInDebug(MessageObject message) {
        if (!BuildVars.DEBUG_VERSION || message == null || message.type != MessageObject.TYPE_VIDEO) {
            return;
        }
        String hash = findHash(message.getDocument());
        if (hash == null) {
            return;
        }
        File file = getCacheFile(hash);
        int thumbCount = message.photoThumbs != null ? message.photoThumbs.size() : 0;
        EmotionDebugReport.onState(hash, "marker " + yesNo(message.isVideoStickerDisplay()) + ", cached " + yesNo(file != null && file.exists())
                + ", inline preview " + yesNo(message.strippedThumb != null) + ", thumbs " + thumbCount);
    }

    /** Debug builds note when the video was set on screen and which file it played from. */
    public static void reportVideoShownInDebug(MessageObject message) {
        if (!BuildVars.DEBUG_VERSION) {
            return;
        }
        String hash = getHash(message);
        if (hash != null) {
            EmotionDebugReport.onVideoShown(hash, message.cachedQuality != null);
        }
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String findHash(TLRPC.Document document) {
        if (document == null || document.attributes == null) {
            return null;
        }
        for (int i = 0, size = document.attributes.size(); i < size; i++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(i);
            if (attribute instanceof TLRPC.TL_documentAttributeFilename && attribute.file_name != null) {
                Matcher matcher = FILE_NAME_PATTERN.matcher(attribute.file_name);
                return matcher.matches() ? matcher.group(1) : null;
            }
        }
        return null;
    }

    /** Returns the fetch in progress for this sticker, starting one if needed; null if it shouldn't be fetched now. */
    private static Fetch startFetch(String hash) {
        Fetch existing = fetches.get(hash);
        if (existing != null) {
            return existing;
        }
        long now = System.currentTimeMillis();
        Long lastFailure = recentFailures.get(hash);
        if (lastFailure != null && now - lastFailure < FAILURE_BACKOFF_MS) {
            return null;
        }
        File target = getCacheFile(hash);
        if (target == null || target.exists()) {
            return null;
        }

        Fetch fetch = new Fetch();
        fetch.waitOver = now - lastNetworkFailureTime < FAILURE_BACKOFF_MS;
        fetches.put(hash, fetch);
        AndroidUtilities.runOnUIThread(() -> settle(hash, fetch), MAX_PLAYBACK_WAIT_MS);
        downloadExecutor.execute(() -> download(hash, target, fetch));
        return fetch;
    }

    private static void download(String hash, File target, Fetch fetch) {
        boolean networkFailed = false;
        final Timing timing = new Timing();
        timing.queueMs = System.currentTimeMillis() - fetch.startTime;
        try {
            fetchToFile(hash, target, timing);
        } catch (Exception e) {
            networkFailed = true;
            FileLog.e("EmotionStickers: fetch failed", e);
        }
        final boolean success = timing.bytes > 0;
        final boolean networkFailure = networkFailed;
        AndroidUtilities.runOnUIThread(() -> {
            fetches.remove(hash);
            long now = System.currentTimeMillis();
            if (!success) {
                recentFailures.put(hash, now);
            }
            if (networkFailure) {
                lastNetworkFailureTime = now;
            }
            reportTiming(hash, success, networkFailure, now - fetch.startTime, timing);
            settle(hash, fetch);
        });
        if (success) {
            trimCache(target);
        }
    }

    private static void settle(String hash, Fetch fetch) {
        fetch.waitOver = true;
        ArrayList<WeakReference<Listener>> listeners = new ArrayList<>(fetch.listeners);
        fetch.listeners.clear();
        for (WeakReference<Listener> reference : listeners) {
            Listener listener = reference.get();
            if (listener != null) {
                listener.onEmotionStickerSettled(hash);
            }
        }
    }

    /** Leaves timing.bytes at 0 if the backend doesn't have this sticker. */
    private static void fetchToFile(String hash, File target, Timing timing) throws IOException {
        long requestStart = System.currentTimeMillis();
        // Not disconnected on success, so the connection can be reused by the next fetch.
        HttpURLConnection connection = (HttpURLConnection) new URL(EmotionApiConfig.BASE_URL + "/s/" + hash + ".mp4").openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        int status = connection.getResponseCode();
        long bodyStart = System.currentTimeMillis();
        timing.responseMs = bodyStart - requestStart;
        timing.edgeCache = connection.getHeaderField("cf-cache-status");
        if (status == HttpURLConnection.HTTP_NOT_FOUND) {
            connection.disconnect();
            return;
        }
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Sticker fetch failed (" + status + ")");
        }
        File temp = new File(target.getPath() + ".tmp");
        long total = 0;
        try (InputStream is = connection.getInputStream(); OutputStream os = new FileOutputStream(temp)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                total += read;
                if (total > MAX_FILE_BYTES) {
                    throw new IOException("Sticker file too large");
                }
                os.write(buffer, 0, read);
            }
        } catch (IOException e) {
            temp.delete();
            throw e;
        }
        if (total == 0 || !temp.renameTo(target)) {
            temp.delete();
            throw new IOException("Could not store sticker file");
        }
        timing.bodyMs = System.currentTimeMillis() - bodyStart;
        timing.bytes = total;
    }

    private static File getCacheFile(String hash) {
        File base = ApplicationLoader.applicationContext.getCacheDir();
        if (base == null) {
            return null;
        }
        File dir = new File(base, CACHE_DIR_NAME);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        return new File(dir, hash + ".mp4");
    }

    private static void trimCache(File justAdded) {
        File dir = justAdded.getParentFile();
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) {
            return;
        }
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        if (total <= MAX_CACHE_BYTES) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= MAX_CACHE_BYTES) {
                break;
            }
            if (!file.equals(justAdded)) {
                total -= file.length();
                file.delete();
            }
        }
    }

    /** Logs how long each fetch took, and adds it to the debug report in debug builds. */
    private static void reportTiming(String hash, boolean success, boolean networkFailure, long elapsedMs, Timing timing) {
        String text;
        if (success) {
            // "ui" is time spent waiting for the main thread after the download finished, which is
            // large right after a cold start and isn't time the network took.
            long uiMs = elapsedMs - timing.queueMs - timing.responseMs - timing.bodyMs;
            text = elapsedMs + " ms, " + (timing.bytes / 1024) + " KB, edge " + timing.edgeCache
                    + " (queue " + timing.queueMs + ", response " + timing.responseMs + ", body " + timing.bodyMs + ", ui " + uiMs + ")";
        } else if (networkFailure) {
            text = "failed after " + elapsedMs + " ms";
        } else {
            text = "not on backend yet (" + elapsedMs + " ms)";
        }
        FileLog.d("EmotionStickers: fetch " + text);
        EmotionDebugReport.onFetchDone(hash, text);
    }
}
