package org.telegram.emotion;

import com.google.gson.Gson;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Client for emotion-backend's presigned-upload flow:
 * requests a short-lived R2 PUT URL from the Worker, then PUTs the file
 * straight to R2. See emotion-backend POST /api/uploads.
 */
public class EmotionUploadApi {

    private static final Gson gson = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int UPLOAD_READ_TIMEOUT_MS = 60000;

    public interface UploadCallback {
        void onSuccess(String objectKey);
        void onError(Exception error);
    }

    private static class UploadRequestBody {
        long telegramUserId;
        String contentType;
        long fileSizeBytes;
        String packId;
    }

    private static class UploadRequestResponse {
        String uploadId;
        String uploadUrl;
        String objectKey;
        long expiresAt;
    }

    public static void uploadSticker(long telegramUserId, File file, String contentType, String packId, UploadCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                UploadRequestResponse response = requestUploadUrl(telegramUserId, contentType, file.length(), packId);
                putFile(response.uploadUrl, file, contentType);
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(response.objectKey));
            } catch (Exception e) {
                FileLog.e("EmotionUploadApi: upload failed", e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(e));
            }
        });
    }

    private static UploadRequestResponse requestUploadUrl(long telegramUserId, String contentType, long fileSizeBytes, String packId) throws IOException {
        UploadRequestBody body = new UploadRequestBody();
        body.telegramUserId = telegramUserId;
        body.contentType = contentType;
        body.fileSizeBytes = fileSizeBytes;
        body.packId = packId;

        byte[] payload = gson.toJson(body).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(EmotionApiConfig.BASE_URL + "/api/uploads").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(CONNECT_TIMEOUT_MS);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(payload);
        }

        int status = connection.getResponseCode();
        String responseBody = readStream(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
        connection.disconnect();

        if (status < 200 || status >= 300) {
            throw new IOException("Upload request failed (" + status + "): " + responseBody);
        }
        return gson.fromJson(responseBody, UploadRequestResponse.class);
    }

    private static void putFile(String uploadUrl, File file, String contentType) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(uploadUrl).openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setFixedLengthStreamingMode((int) file.length());
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(UPLOAD_READ_TIMEOUT_MS);

        try (OutputStream os = connection.getOutputStream(); InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
        }

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            String responseBody = readStream(connection.getErrorStream());
            connection.disconnect();
            throw new IOException("R2 upload failed (" + status + "): " + responseBody);
        }
        connection.disconnect();
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }
        return bos.toString("UTF-8");
    }
}
