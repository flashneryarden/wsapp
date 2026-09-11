import SwiftUI

enum TaskCategory: String, CaseIterable, Hashable, Identifiable {
    case school
    case family
    case friends
    case other

    var id: String { rawValue }

    var label: String {
        rawValue.capitalized
    }

    var color: Color {
        switch self {
        case .school: return .blue
        case .family: return .green
        case .friends: return .orange
        case .other: return .gray
        }
    }

    static func effective(stored: String?, fields: [String?]) -> TaskCategory {
        if let stored, let category = TaskCategory(rawValue: stored.lowercased()) {
            return category
        }

        let text = fields.compactMap { $0 }.joined(separator: " ").lowercased()
        if contains(text, hints: schoolHints) { return .school }
        if contains(text, hints: familyHints) { return .family }
        if contains(text, hints: friendsHints) { return .friends }
        return .other
    }

    private static func contains(_ text: String, hints: [String]) -> Bool {
        hints.contains { text.contains($0.lowercased()) }
    }

    private static let schoolHints = [
        "school", "study", "studies", "homework", "exam", "class", "teacher", "lesson",
        "בית ספר", "ביה\"ס", "שיעור", "מבחן", "בחינה", "כיתה", "מורה", "תלמיד",
        "לימוד", "שיעורי בית", "מטלה", "לימודים", "אוניברסיטה",
    ]

    private static let familyHints = [
        "family", "mom", "dad", "grandma", "grandpa", "home",
        "משפח", "אמא", "אבא", "סבא", "סבתא", "אח ", "אחות", "דוד", "דודה", "בית",
    ]

    private static let friendsHints = [
        "friend", "party", "hangout", "buddy", "חבר", "חברים", "חברה", "מסיבה", "בילוי", "ביחד",
    ]
}
