import FirebaseFirestore
import Foundation

struct TaskItem: Identifiable, Hashable {
    let id: String
    let numericID: Int?
    var origSender: String
    var origChatName: String
    var text: String
    var summary: String
    var actionItems: [String]
    var createdAt: String
    var status: String
    var completedAt: String?
    var notes: [String]
    var critical: Bool?
    var category: String?
    var dueDate: String?

    init?(document: QueryDocumentSnapshot) {
        self.init(documentID: document.documentID, data: document.data())
    }

    init?(document: DocumentSnapshot) {
        guard let data = document.data() else { return nil }
        self.init(documentID: document.documentID, data: data)
    }

    init?(documentID: String, data: [String: Any]) {
        numericID = (data["id"] as? Int)
            ?? (data["id"] as? NSNumber)?.intValue
            ?? Int(documentID)
        id = documentID
        origSender = (data["origSender"] as? String) ?? ""
        origChatName = (data["origChatName"] as? String) ?? ""
        let messageText = (data["text"] as? String) ?? ""
        text = messageText
        summary = (data["summary"] as? String) ?? messageText
        actionItems = (data["actionItems"] as? [String]) ?? []
        createdAt = (data["createdAt"] as? String) ?? ""
        status = (data["status"] as? String) ?? "pending"
        completedAt = data["completedAt"] as? String
        notes = (data["notes"] as? [String]) ?? []
        critical = data["critical"] as? Bool
        category = data["category"] as? String
        dueDate = data["dueDate"] as? String
    }

    var isPending: Bool { status == "pending" }
    var isDone: Bool { status == "done" }
    var displayID: String {
        if let numericID, id == String(numericID) {
            return String(numericID)
        }
        return String(id.prefix(6)).uppercased()
    }

    var effectiveCategory: TaskCategory {
        TaskCategory.effective(
            stored: category,
            fields: [origChatName, origSender, summary, text, actionItems.joined(separator: " ")]
        )
    }

    var effectiveDueDate: String? {
        if let dueDate, !dueDate.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return dueDate
        }

        let expression = try? NSRegularExpression(pattern: #"due:\s*([^\]\n]+)"#, options: .caseInsensitive)
        for item in actionItems {
            let range = NSRange(item.startIndex..., in: item)
            guard let match = expression?.firstMatch(in: item, range: range),
                  let valueRange = Range(match.range(at: 1), in: item) else {
                continue
            }
            let value = String(item[valueRange]).trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty { return value }
        }
        return nil
    }

    var isEffectivelyCritical: Bool {
        if let critical { return critical }
        let combined = ([summary, text] + actionItems).joined(separator: " ").lowercased()
        return Self.criticalKeywords.contains { combined.contains($0.lowercased()) }
    }

    var createdDate: Date? {
        ISO8601DateFormatter.withFractionalSeconds.date(from: createdAt)
            ?? ISO8601DateFormatter().date(from: createdAt)
    }

    var resolvedDueDate: Date? {
        DueDateFormatter.resolve(effectiveDueDate, createdAt: createdAt)
    }

    private static let criticalKeywords = [
        "urgent", "asap", "immediately", "emergency", "critical", "deadline",
        "right now", "important!", "!!!", "דחוף", "מיידי", "מיד", "חירום",
        "קריטי", "סכנה", "עכשיו", "חשוב מאוד", "בהול", "אסון",
    ]
}

extension ISO8601DateFormatter {
    static let withFractionalSeconds: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}
