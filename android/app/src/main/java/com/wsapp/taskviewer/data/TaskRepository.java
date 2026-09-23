package com.wsapp.taskviewer.data;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.wsapp.taskviewer.model.Task;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class TaskRepository {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final MutableLiveData<List<Task>> tasks = new MutableLiveData<>(new ArrayList<>());
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<SyncState> syncState =
            new MutableLiveData<>(new SyncState(true, false, null));
    private ListenerRegistration listenerRegistration;

    public TaskRepository() {
        listen();
    }

    public LiveData<List<Task>> getTasks() {
        return tasks;
    }

    public LiveData<String> getError() {
        return error;
    }

    public LiveData<SyncState> getSyncState() {
        return syncState;
    }

    public void listen() {
        if (listenerRegistration != null) listenerRegistration.remove();
        listenerRegistration = db.collection("tasks")
                .addSnapshotListener(MetadataChanges.INCLUDE, (snapshots, failure) -> {
                    if (failure != null) {
                        error.setValue(failure.getMessage());
                        SyncState previous = syncState.getValue();
                        syncState.setValue(new SyncState(
                                previous == null || previous.fromCache,
                                previous != null && previous.hasPendingWrites,
                                failure.getMessage()));
                        return;
                    }
                    if (snapshots == null) return;
                    List<Task> loaded = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        Task task = document.toObject(Task.class);
                        task.setDocumentId(document.getId());
                        loaded.add(task);
                    }
                    tasks.setValue(loaded);
                    error.setValue(null);
                    syncState.setValue(new SyncState(
                            snapshots.getMetadata().isFromCache(),
                            snapshots.getMetadata().hasPendingWrites(),
                            null));
                });
    }

    public void createTask(String text, Runnable onSuccess, ErrorCallback onFailure) {
        DocumentReference reference = db.collection("tasks").document();
        Map<String, Object> task = new HashMap<>();
        task.put("origSender", "android");
        task.put("origChatName", "android");
        task.put("text", text);
        task.put("summary", text);
        task.put("actionItems", new ArrayList<>());
        java.text.SimpleDateFormat timestamp =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        task.put("createdAt", timestamp.format(new Date()));
        task.put("status", "pending");
        task.put("completedAt", null);
        task.put("notes", new ArrayList<>());
        reference.set(task)
                .addOnSuccessListener(value -> onSuccess.run())
                .addOnFailureListener(onFailure::onError);
    }

    public void deleteTask(Task task, Runnable onSuccess, ErrorCallback onFailure) {
        db.collection("tasks").document(task.getDocumentId())
                .delete()
                .addOnSuccessListener(value -> onSuccess.run())
                .addOnFailureListener(onFailure::onError);
    }

    public void deleteAll(Runnable onSuccess, ErrorCallback onFailure) {
        db.collection("tasks").get()
                .addOnSuccessListener(snapshots -> {
                    if (snapshots.isEmpty()) {
                        onSuccess.run();
                        return;
                    }
                    List<DocumentReference> references = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        references.add(document.getReference());
                    }
                    deleteBatches(references, 0, onSuccess, onFailure);
                })
                .addOnFailureListener(onFailure::onError);
    }

    private void deleteBatches(
            List<DocumentReference> references,
            int start,
            Runnable onSuccess,
            ErrorCallback onFailure) {
        if (start >= references.size()) {
            onSuccess.run();
            return;
        }
        int end = Math.min(start + 500, references.size());
        WriteBatch batch = db.batch();
        for (int i = start; i < end; i++) {
            batch.delete(references.get(i));
        }
        batch.commit()
                .addOnSuccessListener(value ->
                        deleteBatches(references, end, onSuccess, onFailure))
                .addOnFailureListener(onFailure::onError);
    }

    public FirebaseFirestore getDatabase() {
        return db;
    }

    public void close() {
        if (listenerRegistration != null) {
            listenerRegistration.remove();
            listenerRegistration = null;
        }
    }

    public interface ErrorCallback {
        void onError(Exception error);
    }

    public static final class SyncState {
        public final boolean fromCache;
        public final boolean hasPendingWrites;
        public final String error;

        public SyncState(boolean fromCache, boolean hasPendingWrites, String error) {
            this.fromCache = fromCache;
            this.hasPendingWrites = hasPendingWrites;
            this.error = error;
        }
    }
}
