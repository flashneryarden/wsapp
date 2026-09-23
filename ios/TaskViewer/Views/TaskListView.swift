import SwiftUI

struct TaskListView: View {
    enum SortMode: String, CaseIterable, Hashable, Identifiable {
        case critical = "Critical First"
        case dueDate = "Due Date"
        case newest = "Newest First"
        case group = "By Group"
        case sender = "By Sender"
        case category = "By Category"

        var id: String { rawValue }
    }

    @StateObject private var repository = TaskRepository()
    @EnvironmentObject private var notificationRouter: NotificationRouter
    @State private var path: [String] = []
    @State private var statusFilter: String?
    @State private var groupFilter: String?
    @State private var senderFilter: String?
    @State private var categoryFilter: TaskCategory?
    @State private var sortMode: SortMode = .critical
    @State private var showingNewTask = false
    @State private var newTaskText = ""
    @State private var deleteAllConfirmation = false
    @State private var oldTaskDays: Int?

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                if hasFilters {
                    Button(action: clearFilters) {
                        HStack {
                            Text(filterDescription)
                                .font(.footnote.bold())
                            Spacer()
                            Text("Tap to clear")
                                .font(.caption)
                        }
                        .foregroundStyle(.orange)
                        .padding(.horizontal)
                        .padding(.vertical, 10)
                        .frame(maxWidth: .infinity)
                        .background(Color.orange.opacity(0.12))
                    }
                    .buttonStyle(.plain)
                }

                if visibleTasks.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "checklist")
                            .font(.system(size: 42))
                            .foregroundStyle(.secondary)
                        Text(hasFilters ? "No Matching Tasks" : "No Tasks Yet")
                            .font(.headline)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(visibleTasks) { task in
                            NavigationLink(value: task.id) {
                                TaskRowView(task: task)
                            }
                            .swipeActions {
                                Button(role: .destructive) {
                                    Task { await repository.deleteTask(task.id) }
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                        }
                    }
                    .refreshable {
                        repository.startListening()
                    }
                }
            }
            .navigationTitle("Tasks")
            .navigationDestination(for: String.self) { taskID in
                TaskDetailView(taskID: taskID, repository: repository)
            }
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    NavigationLink {
                        PillBoxView()
                    } label: {
                        Image(systemName: "pills")
                    }

                    Menu {
                        sortMenu
                        statusMenu
                        valueFilterMenus
                        Divider()
                        Button("Clear Filters", action: clearFilters)
                        Menu("Delete Old Tasks") {
                            ForEach([30, 60, 90], id: \.self) { days in
                                Button("Older than \(days) days") {
                                    oldTaskDays = days
                                }
                            }
                        }
                        Button("Delete All Tasks", role: .destructive) {
                            deleteAllConfirmation = true
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }

                    Button {
                        showingNewTask = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .alert("New Task", isPresented: $showingNewTask) {
                TextField("Task description", text: $newTaskText)
                Button("Cancel", role: .cancel) {
                    newTaskText = ""
                }
                Button("Add") {
                    let text = newTaskText.trimmingCharacters(in: .whitespacesAndNewlines)
                    newTaskText = ""
                    if !text.isEmpty {
                        Task { await repository.createTask(text: text) }
                    }
                }
            }
            .alert("Delete All Tasks?", isPresented: $deleteAllConfirmation) {
                Button("Cancel", role: .cancel) {}
                Button("Delete All", role: .destructive) {
                    Task { await repository.deleteAllTasks() }
                }
            } message: {
                Text("This cannot be undone.")
            }
            .alert(
                "Delete Old Tasks?",
                isPresented: Binding(
                    get: { oldTaskDays != nil },
                    set: { if !$0 { oldTaskDays = nil } }
                )
            ) {
                Button("Cancel", role: .cancel) { oldTaskDays = nil }
                Button("Delete", role: .destructive) {
                    guard let days = oldTaskDays else { return }
                    oldTaskDays = nil
                    Task { await repository.deleteTasks(olderThanDays: days) }
                }
            } message: {
                Text("Delete tasks older than \(oldTaskDays ?? 0) days?")
            }
            .alert(
                "Task Viewer",
                isPresented: Binding(
                    get: { repository.errorMessage != nil },
                    set: { if !$0 { repository.errorMessage = nil } }
                )
            ) {
                Button("OK") { repository.errorMessage = nil }
            } message: {
                Text(repository.errorMessage ?? "")
            }
            .onChange(of: notificationRouter.taskID) { taskID in
                guard let taskID else { return }
                path = [taskID]
                notificationRouter.taskID = nil
            }
        }
    }

    private var visibleTasks: [TaskItem] {
        var result = repository.tasks.filter { task in
            if let statusFilter, task.status != statusFilter { return false }
            if let groupFilter, task.origChatName != groupFilter { return false }
            if let senderFilter, task.origSender != senderFilter { return false }
            if let categoryFilter, task.effectiveCategory != categoryFilter { return false }
            return true
        }

        switch sortMode {
        case .critical:
            result.sort {
                if $0.isEffectivelyCritical != $1.isEffectivelyCritical {
                    return $0.isEffectivelyCritical
                }
                return newestFirst($0, $1)
            }
        case .dueDate:
            result.sort {
                switch ($0.resolvedDueDate, $1.resolvedDueDate) {
                case let (left?, right?): return left < right
                case (_?, nil): return true
                case (nil, _?): return false
                case (nil, nil): return newestFirst($0, $1)
                }
            }
        case .newest:
            result.sort(by: newestFirst)
        case .group:
            result.sort {
                $0.origChatName.localizedCaseInsensitiveCompare($1.origChatName) == .orderedAscending
            }
        case .sender:
            result.sort {
                $0.origSender.localizedCaseInsensitiveCompare($1.origSender) == .orderedAscending
            }
        case .category:
            result.sort { $0.effectiveCategory.label < $1.effectiveCategory.label }
        }
        return result
    }

    private func newestFirst(_ left: TaskItem, _ right: TaskItem) -> Bool {
        let leftDate = left.createdDate ?? .distantPast
        let rightDate = right.createdDate ?? .distantPast
        return leftDate == rightDate ? left.id > right.id : leftDate > rightDate
    }

    private var groups: [String] {
        Set(repository.tasks.map(\.origChatName).filter { !$0.isEmpty }).sorted()
    }

    private var senders: [String] {
        Set(repository.tasks.map(\.origSender).filter { !$0.isEmpty }).sorted()
    }

    private var hasFilters: Bool {
        statusFilter != nil || groupFilter != nil || senderFilter != nil || categoryFilter != nil
    }

    private var filterDescription: String {
        var parts: [String] = []
        if let statusFilter { parts.append("Status: \(statusFilter.capitalized)") }
        if let groupFilter { parts.append("Group: \(groupFilter)") }
        if let senderFilter { parts.append("Sender: \(senderFilter)") }
        if let categoryFilter { parts.append("Category: \(categoryFilter.label)") }
        let hidden = repository.tasks.count - visibleTasks.count
        return "Filtered · \(parts.joined(separator: ", "))\(hidden > 0 ? " (\(hidden) hidden)" : "")"
    }

    @ViewBuilder
    private var sortMenu: some View {
        Menu("Sort") {
            Picker("Sort", selection: $sortMode) {
                ForEach(SortMode.allCases) { mode in
                    Text(mode.rawValue).tag(mode)
                }
            }
        }
    }

    @ViewBuilder
    private var statusMenu: some View {
        Menu("Filter by Status") {
            Button("All Tasks") { statusFilter = nil }
            Button("Pending Only") { statusFilter = "pending" }
            Button("Completed Only") { statusFilter = "done" }
        }
    }

    @ViewBuilder
    private var valueFilterMenus: some View {
        Menu("Filter by Group") {
            Button("All Groups") { groupFilter = nil }
            ForEach(groups, id: \.self) { group in
                Button(group) {
                    groupFilter = group
                    senderFilter = nil
                }
            }
        }
        Menu("Filter by Sender") {
            Button("All Senders") { senderFilter = nil }
            ForEach(senders, id: \.self) { sender in
                Button(sender) {
                    senderFilter = sender
                    groupFilter = nil
                }
            }
        }
        Menu("Filter by Category") {
            Button("All Categories") { categoryFilter = nil }
            ForEach(TaskCategory.allCases) { category in
                Button(category.label) { categoryFilter = category }
            }
        }
    }

    private func clearFilters() {
        statusFilter = nil
        groupFilter = nil
        senderFilter = nil
        categoryFilter = nil
    }
}
