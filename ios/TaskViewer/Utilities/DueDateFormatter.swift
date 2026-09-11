import Foundation

enum DueDateFormatter {
    static func display(_ raw: String?, createdAt: String) -> String? {
        guard let date = resolve(raw, createdAt: createdAt) else {
            return raw?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        }

        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        let target = calendar.startOfDay(for: date)
        guard target >= today else { return nil }

        let difference = calendar.dateComponents([.day], from: today, to: target).day ?? 0
        switch difference {
        case 0: return "Today"
        case 1: return "Tomorrow"
        case 2...6:
            return target.formatted(.dateTime.weekday(.wide))
        default:
            return target.formatted(.dateTime.day().month().year())
        }
    }

    static func resolve(_ raw: String?, createdAt: String) -> Date? {
        guard let raw = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }

        if let date = parseExplicitDate(raw) {
            return date
        }

        let anchor = String(createdAt.prefix(10))
        let base = parseExplicitDate(anchor) ?? Date()
        let lower = raw.lowercased()
        if lower.contains("today") || raw.contains("היום") { return base }
        if lower.contains("tomorrow") || raw.contains("מחר") {
            return Calendar.current.date(byAdding: .day, value: 1, to: base)
        }
        if lower.contains("yesterday") || raw.contains("אתמול") {
            return Calendar.current.date(byAdding: .day, value: -1, to: base)
        }
        return nil
    }

    private static func parseExplicitDate(_ value: String) -> Date? {
        let patterns = [
            (#"(\d{4})-(\d{2})-(\d{2})"#, "yyyy-MM-dd"),
            (#"(\d{1,2})[./](\d{1,2})[./](\d{4})"#, "dd/MM/yyyy"),
        ]

        for (pattern, format) in patterns {
            guard let expression = try? NSRegularExpression(pattern: pattern),
                  let match = expression.firstMatch(
                    in: value,
                    range: NSRange(value.startIndex..., in: value)
                  ),
                  let range = Range(match.range, in: value) else {
                continue
            }

            let candidate = String(value[range]).replacingOccurrences(of: ".", with: "/")
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.dateFormat = format
            return formatter.date(from: candidate)
        }
        return nil
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
