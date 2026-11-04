package org.telegram.emotion;

import com.google.gson.Gson;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Client for emotion-backend's sticker pack endpoints:
 * GET /api/packs (the user's own packs) and POST /api/packs (create a pack).
 */
public class EmotionPacksApi {

    private static final Gson gson = new Gson();
    private static final int TIMEOUT_MS = 15000;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception error);
    }

    public static class Pack {
        public String id;
        public String title;
        public String status;
        public int stickerCount;
    }

    private static class ListPacksResponse {
        Pack[] packs;
    }

    private static class CreatePackBody {
        long telegramUserId;
        String title;
    }

    public static void listPacks(long telegramUserId, Callback<List<Pack>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String responseBody = send("GET", "/api/packs?telegramUserId=" + telegramUserId, null);
                ListPacksResponse response = gson.fromJson(responseBody, ListPacksResponse.class);
                List<Pack> packs = response != null && response.packs != null ? new ArrayList<>(Arrays.asList(response.packs)) : new ArrayList<>();
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(packs));
            } catch (Exception e) {
                FileLog.e("EmotionPacksApi: list packs failed", e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(e));
            }
        });
    }

    public static void createPack(long telegramUserId, String title, Callback<Pack> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                CreatePackBody body = new CreatePackBody();
                body.telegramUserId = telegramUserId;
                body.title = title;
                String responseBody = send("POST", "/api/packs", gson.toJson(body));
                Pack pack = gson.fromJson(responseBody, Pack.class);
                if (pack == null || pack.id == null) {
                    throw new IOException("Malformed create-pack response: " + responseBody);
                }
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(pack));
            } catch (Exception e) {
                FileLog.e("EmotionPacksApi: create pack failed", e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(e));
            }
        });
    }

    private static String send(String method, String path, String jsonBody) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(EmotionApiConfig.BASE_URL + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        if (jsonBody != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = connection.getResponseCode();
        String responseBody = EmotionUploadApi.readStream(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
        connection.disconnect();

        if (status < 200 || status >= 300) {
            throw new IOException("Request failed (" + status + "): " + responseBody);
        }
        return responseBody;
    }
}
