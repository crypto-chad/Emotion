package org.telegram.emotion;

import android.media.MediaMetadataRetriever;

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
        long durationMs;
        Integer width;
        Integer height;
    }

    private static class VideoMetadata {
        long durationMs;
        int width;
        int height;
    }

    private static class UploadRequestResponse {
        String uploadId;
        String uploadUrl;
        String objectKey;
        long expiresAt;
    }

    /** Maps a file extension to a content type the backend accepts, or null if unsupported. */
    public static String contentTypeFor(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        } else if (name.endsWith(".webm")) {
            return "video/webm";
        } else if (name.endsWith(".mov")) {
            return "video/quicktime";
        }
        return null;
    }

    /**
     * Uploads a video as a sticker into the given pack. The backend registers it in the
     * stickers table once R2 confirms the object, so the pack must already exist and
     * belong to the user (see {@link EmotionPacksApi}).
     */
    public static void uploadSticker(long telegramUserId, File file, String contentType, String packId, UploadCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                VideoMetadata metadata = readVideoMetadata(file);
                UploadRequestResponse response = requestUploadUrl(telegramUserId, contentType, file.length(), packId, metadata);
                putFile(response.uploadUrl, file, contentType);
                AndroidUtilities.runOnUIThread(() -> callback.onSuccess(response.objectKey));
            } catch (Exception e) {
                FileLog.e("EmotionUploadApi: upload failed", e);
                AndroidUtilities.runOnUIThread(() -> callback.onError(e));
            }
        });
    }

    /** Reads exact duration and (rotation-corrected) dimensions; the gallery's own values are truncated to whole seconds. */
    private static VideoMetadata readVideoMetadata(File file) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);

            VideoMetadata metadata = new VideoMetadata();
            metadata.durationMs = duration != null ? Long.parseLong(duration) : 0;
            metadata.width = width != null ? Integer.parseInt(width) : 0;
            metadata.height = height != null ? Integer.parseInt(height) : 0;
            if (rotation != null) {
                int degrees = Integer.parseInt(rotation);
                if (degrees == 90 || degrees == 270) {
                    int swap = metadata.width;
                    metadata.width = metadata.height;
                    metadata.height = swap;
                }
            }
            if (metadata.durationMs <= 0) {
                throw new IOException("Could not read video duration");
            }
            return metadata;
        } catch (RuntimeException e) {
            throw new IOException("Could not read video metadata", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
    }

    private static UploadRequestResponse requestUploadUrl(long telegramUserId, String contentType, long fileSizeBytes, String packId, VideoMetadata metadata) throws IOException {
        UploadRequestBody body = new UploadRequestBody();
        body.telegramUserId = telegramUserId;
        body.contentType = contentType;
        body.fileSizeBytes = fileSizeBytes;
        body.packId = packId;
        body.durationMs = metadata.durationMs;
        body.width = metadata.width > 0 ? metadata.width : null;
        body.height = metadata.height > 0 ? metadata.height : null;

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

    static String readStream(InputStream stream) throws IOException {
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
