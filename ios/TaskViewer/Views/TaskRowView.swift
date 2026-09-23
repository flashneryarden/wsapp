import SwiftUI

struct TaskRowView: View {
    let task: TaskItem

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(task.isDone ? "✅" : "⏳")
                Text("#\(task.displayID)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if task.isEffectivelyCritical {
                    Text("CRITICAL")
                        .font(.caption2.bold())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(.red, in: Capsule())
                }
                Spacer()
                categoryBadge
            }

            Text(task.summary)
                .font(.headline)

            Text("\(task.origSender) (\(task.origChatName))")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            HStack {
                if let created = task.createdDate {
                    Text(created.formatted(date: .numeric, time: .shortened))
                }
                Spacer()
                if !task.actionItems.isEmpty {
                    Text("\(task.actionItems.count) action item\(task.actionItems.count == 1 ? "" : "s")")
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            if let due = DueDateFormatter.display(task.effectiveDueDate, createdAt: task.createdAt) {
                Text("📅 Due: \(due)")
                    .font(.subheadline)
                    .foregroundStyle(.orange)
            }
        }
        .padding(.vertical, 5)
    }

    private var categoryBadge: some View {
        Text(task.effectiveCategory.label.uppercased())
            .font(.caption2.bold())
            .foregroundStyle(.white)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(task.effectiveCategory.color, in: Capsule())
    }
}
