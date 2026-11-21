package com.wsapp.taskviewer;

import android.util.Log;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public final class DeviceRegistration {
    private DeviceRegistration() {
    }

    public static void registerCurrentToken() {
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(DeviceRegistration::saveToken)
                .addOnFailureListener(error ->
                        Log.w("DeviceRegistration", "Unable to obtain FCM token", error));
    }

    public static void saveToken(String token) {
        if (token == null || token.isEmpty()) return;

        Map<String, Object> device = new HashMap<>();
        device.put("token", token);
        device.put("platform", "android");
        device.put("updatedAt", FieldValue.serverTimestamp());

        FirebaseFirestore.getInstance()
                .collection("devices")
                .document(hashToken(token))
                .set(device, SetOptions.merge())
                .addOnFailureListener(error ->
                        Log.w("DeviceRegistration", "Unable to register FCM token", error));
    }

    public static com.google.android.gms.tasks.Task<Void> recordSkippedGroup(String groupId) {
        return FirebaseMessaging.getInstance().getToken().onSuccessTask(token -> {
            if (token == null || token.isEmpty()) {
                throw new IllegalStateException("Unable to obtain FCM token for group preference");
            }
            Map<String, Object> device = new HashMap<>();
            device.put("token", token);
            device.put("platform", "android");
            device.put("updatedAt", FieldValue.serverTimestamp());
            device.put("skippedGroupIds", FieldValue.arrayUnion(groupId));
            return FirebaseFirestore.getInstance()
                    .collection("devices")
                    .document(hashToken(token))
                    .set(device, SetOptions.merge());
        });
    }

    private static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
