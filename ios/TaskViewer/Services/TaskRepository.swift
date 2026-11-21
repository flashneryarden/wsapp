import Combine
import FirebaseFirestore
import FirebaseMessaging
import Foundation

@MainActor
final class TaskRepository: ObservableObject {
    @Published private(set) var tasks: [TaskItem] = []
    @Published var errorMessage: String?

    private let db = Firestore.firestore()
    private var listener: ListenerRegistration?

    init() {
        startListening()
    }

    deinit {
        listener?.remove()
    }

    func startListening() {
        listener?.remove()
        listener = db.collection("tasks")
            .addSnapshotListener { [weak self] snapshot, error in
                Task { @MainActor in
                    guard let self else { return }
                    if let error {
                        self.errorMessage = "Error loading tasks: \(error.localizedDescription)"
                        return
                    }
                    self.tasks = (snapshot?.documents.compactMap { TaskItem(document: $0) } ?? [])
                        .sorted { ($0.createdDate ?? .distantPast) > ($1.createdDate ?? .distantPast) }
                }
            }
    }

    func createTask(text: String) async {
        do {
            try await db.collection("tasks").addDocument(data: [
                "origSender": "ios",
                "origChatName": "ios",
                "text": text,
                "summary": text,
                "actionItems": [],
                "createdAt": Self.nowISO(),
                "status": "pending",
                "completedAt": NSNull(),
                "notes": [],
                "critical": false,
                "category": "other",
                "dueDate": NSNull(),
            ])
        } catch {
            errorMessage = "Failed to create task: \(error.localizedDescription)"
        }
    }

    func toggleStatus(_ task: TaskItem) async {
        let done = task.isPending
        let completedAt: Any
        if done {
            completedAt = Self.nowISO()
        } else {
            completedAt = NSNull()
        }
        await updateTask(task.id, fields: [
            "status": done ? "done" : "pending",
            "completedAt": completedAt,
        ])
    }

    func addNote(_ note: String, to taskID: String) async {
        await updateTask(taskID, fields: ["notes": FieldValue.arrayUnion([note])])
    }

    func setDueDate(_ date: Date?, for taskID: String) async {
        let value: Any
        if let date {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.dateFormat = "yyyy-MM-dd"
            value = formatter.string(from: date)
        } else {
            value = NSNull()
        }
        await updateTask(taskID, fields: ["dueDate": value])
    }

    func setCategory(_ category: TaskCategory, for taskID: String) async {
        await updateTask(taskID, fields: ["category": category.rawValue])
    }

    func deleteTask(_ taskID: String) async {
        do {
            try await db.collection("tasks").document(taskID).delete()
        } catch {
            errorMessage = "Failed to delete task: \(error.localizedDescription)"
        }
    }

    func deleteAllTasks() async {
        await deleteDocuments(db.collection("tasks"))
    }

    func deleteTasks(olderThanDays days: Int) async {
        do {
            let snapshot = try await db.collection("tasks").getDocuments()
            let cutoff = Date().addingTimeInterval(-Double(days) * 86_400)
            let references = snapshot.documents.compactMap { document -> DocumentReference? in
                guard let task = TaskItem(document: document),
                      let created = task.createdDate,
                      created < cutoff else {
                    return nil
                }
                return document.reference
            }
            try await deleteReferences(references)
        } catch {
            errorMessage = "Failed to delete old tasks: \(error.localizedDescription)"
        }
    }

    func skipGroup(_ group: String, deleteExisting: Bool) async {
        do {
            let existing = try await db.collection("skippedGroups")
                .whereField("name", isEqualTo: group)
                .limit(to: 1)
                .getDocuments()
            let groupID: String
            if let document = existing.documents.first {
                groupID = document.documentID
            } else {
                let reference = try await db.collection("skippedGroups").addDocument(data: [
                    "name": group,
                    "skippedAt": Self.nowISO(),
                ])
                groupID = reference.documentID
            }
            do {
                let token = try await Messaging.messaging().token()
                guard !token.isEmpty else {
                    throw NSError(
                        domain: "TaskRepository",
                        code: 1,
                        userInfo: [NSLocalizedDescriptionKey: "Unable to obtain FCM token for group preference"]
                    )
                }
                try await db.collection("devices").document(token.sha256).setData([
                    "token": token,
                    "platform": "ios",
                    "updatedAt": FieldValue.serverTimestamp(),
                    "skippedGroupIds": FieldValue.arrayUnion([groupID]),
                ], merge: true)
            } catch {
                errorMessage = "Group skipped globally, but saving this device's preference failed: \(error.localizedDescription)"
            }
            if deleteExisting {
                await deleteDocuments(
                    db.collection("tasks").whereField("origChatName", isEqualTo: group)
                )
            }
        } catch {
            errorMessage = "Failed to skip group: \(error.localizedDescription)"
        }
    }

    private func updateTask(_ taskID: String, fields: [AnyHashable: Any]) async {
        do {
            try await db.collection("tasks").document(taskID).updateData(fields)
        } catch {
            errorMessage = "Failed to update task: \(error.localizedDescription)"
        }
    }

    private func deleteDocuments(_ query: Query) async {
        do {
            let snapshot = try await query.getDocuments()
            try await deleteReferences(snapshot.documents.map(\.reference))
        } catch {
            errorMessage = "Failed to delete tasks: \(error.localizedDescription)"
        }
    }

    private func deleteReferences(_ references: [DocumentReference]) async throws {
        for start in stride(from: 0, to: references.count, by: 500) {
            let end = min(start + 500, references.count)
            let batch = db.batch()
            references[start..<end].forEach { reference in
                batch.deleteDocument(reference)
            }
            try await batch.commit()
        }
    }

    private static func nowISO() -> String {
        ISO8601DateFormatter.withFractionalSeconds.string(from: Date())
    }
}
