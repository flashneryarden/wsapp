package com.wsapp.taskviewer.data;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
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

    public void listen() {
        if (listenerRegistration != null) listenerRegistration.remove();
        listenerRegistration = db.collection("tasks")
                .orderBy("id", Query.Direction.DESCENDING)
                .addSnapshotListener((snapshots, failure) -> {
                    if (failure != null) {
                        error.setValue(failure.getMessage());
                        return;
                    }
                    if (snapshots == null) return;
                    List<Task> loaded = new ArrayList<>();
                    for (QueryDocumentSnapshot document : snapshots) {
                        loaded.add(document.toObject(Task.class));
                    }
                    tasks.setValue(loaded);
                    error.setValue(null);
                });
    }

    public void createTask(String text, Runnable onSuccess, ErrorCallback onFailure) {
        db.collection("tasks")
                .orderBy("id", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener(snapshots -> {
                    int nextId = 1;
                    for (QueryDocumentSnapshot document : snapshots) {
                        nextId = document.toObject(Task.class).getId() + 1;
                    }
                    Map<String, Object> task = new HashMap<>();
                    task.put("id", nextId);
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
                    task.put("reminderEnabled", false);
                    int taskId = nextId;
                    db.collection("tasks").document(String.valueOf(taskId))
                            .set(task)
                            .addOnSuccessListener(value -> onSuccess.run())
                            .addOnFailureListener(onFailure::onError);
                })
                .addOnFailureListener(onFailure::onError);
    }

    public void deleteTask(Task task, Runnable onSuccess, ErrorCallback onFailure) {
        db.collection("tasks").document(String.valueOf(task.getId()))
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
}
