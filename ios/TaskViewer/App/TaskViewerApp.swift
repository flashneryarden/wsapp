import SwiftUI

@main
struct TaskViewerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var notificationRouter = NotificationRouter.shared

    var body: some Scene {
        WindowGroup {
            TaskListView()
                .environmentObject(notificationRouter)
        }
    }
}
