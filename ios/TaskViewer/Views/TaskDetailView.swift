import SwiftUI

struct TaskDetailView: View {
    let taskID: String
    @ObservedObject var repository: TaskRepository
    @Environment(\.dismiss) private var dismiss

    @State private var showingNote = false
    @State private var noteText = ""
    @State private var showingDelete = false
    @State private var showingSkip = false
    @State private var showingDatePicker = false
    @State private var selectedDate = Date()

    private var task: TaskItem? {
        repository.tasks.first { $0.id == taskID }
    }

    var body: some View {
        Group {
            if let task {
                Form {
                    Section {
                        HStack {
                            Text(task.isDone ? "✅" : "⏳")
                                .font(.largeTitle)
                            VStack(alignment: .leading) {
                                Text("\(task.isEffectivelyCritical ? "🔴 " : "")Task #\(task.displayID)")
                                    .font(.title2.bold())
                                Text("Status: \(task.status)")
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Text("From: \(task.origSender) (\(task.origChatName))")
                        if let created = task.createdDate {
                            Text("Created: \(created.formatted(date: .numeric, time: .shortened))")
                        }
                        if let completed = task.completedAt.flatMap({
                            ISO8601DateFormatter.withFractionalSeconds.date(from: $0)
                        }) {
                            Text("Completed: \(completed.formatted(date: .numeric, time: .shortened))")
                        }
                    }

                    Section("Category and Due Date") {
                        Picker("Category", selection: Binding(
                            get: { task.effectiveCategory },
                            set: { category in
                                Task { await repository.setCategory(category, for: task.id) }
                            }
                        )) {
                            ForEach(TaskCategory.allCases) { category in
                                Text(category.label).tag(category)
                            }
                        }

                        Button {
                            selectedDate = task.resolvedDueDate ?? Date()
                            showingDatePicker = true
                        } label: {
                            HStack {
                                Text("Due Date")
                                Spacer()
                                Text(DueDateFormatter.display(task.effectiveDueDate, createdAt: task.createdAt) ?? "None")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }

                    Section("Message") {
                        Text(task.text)
                    }

                    if !task.summary.isEmpty {
                        Section("Summary") {
                            Text(task.summary)
                        }
                    }

                    if !task.actionItems.isEmpty {
                        Section("Action Items") {
                            ForEach(task.actionItems, id: \.self) { item in
                                Text("• \(item)")
                            }
                        }
                    }

                    Section("Notes") {
                        if task.notes.isEmpty {
                            Text("No notes")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(task.notes, id: \.self) { note in
                                Text(note)
                            }
                        }
                        Button("Add Note") { showingNote = true }
                    }

                    Section {
                        Button(task.isPending ? "Mark Done" : "Reopen") {
                            Task { await repository.toggleStatus(task) }
                        }
                        Button("Skip \(task.origChatName)") {
                            showingSkip = true
                        }
                        .disabled(task.origChatName.isEmpty)
                        Button("Delete Task", role: .destructive) {
                            showingDelete = true
                        }
                    }
                }
                .navigationTitle("Task #\(task.displayID)")
                .navigationBarTitleDisplayMode(.inline)
                .alert("Add Note", isPresented: $showingNote) {
                    TextField("Note text", text: $noteText)
                    Button("Cancel", role: .cancel) { noteText = "" }
                    Button("Add") {
                        let note = noteText.trimmingCharacters(in: .whitespacesAndNewlines)
                        noteText = ""
                        if !note.isEmpty {
                            Task { await repository.addNote(note, to: task.id) }
                        }
                    }
                }
                .confirmationDialog(
                    "Skip \(task.origChatName)?",
                    isPresented: $showingSkip,
                    titleVisibility: .visible
                ) {
                    Button("Skip Only") {
                        Task {
                            await repository.skipGroup(task.origChatName, deleteExisting: false)
                            dismiss()
                        }
                    }
                    Button("Skip & Delete Existing Tasks", role: .destructive) {
                        Task {
                            await repository.skipGroup(task.origChatName, deleteExisting: true)
                            dismiss()
                        }
                    }
                } message: {
                    Text("Future messages from this group will not create tasks.")
                }
                .alert("Delete Task?", isPresented: $showingDelete) {
                    Button("Cancel", role: .cancel) {}
                    Button("Delete", role: .destructive) {
                        Task {
                            await repository.deleteTask(task.id)
                            dismiss()
                        }
                    }
                }
                .sheet(isPresented: $showingDatePicker) {
                    NavigationStack {
                        DatePicker(
                            "Due Date",
                            selection: $selectedDate,
                            displayedComponents: .date
                        )
                        .datePickerStyle(.graphical)
                        .padding()
                        .navigationTitle("Set Due Date")
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button("Clear") {
                                    Task { await repository.setDueDate(nil, for: task.id) }
                                    showingDatePicker = false
                                }
                            }
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Save") {
                                    Task { await repository.setDueDate(selectedDate, for: task.id) }
                                    showingDatePicker = false
                                }
                            }
                        }
                    }
                    .presentationDetents([.medium, .large])
                }
            } else {
                ProgressView("Loading task…")
                    .task {
                        try? await Task.sleep(for: .seconds(3))
                        if task == nil { dismiss() }
                    }
            }
        }
    }
}
