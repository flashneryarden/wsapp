import Combine
import Foundation

@MainActor
final class NotificationRouter: ObservableObject {
    static let shared = NotificationRouter()

    @Published var taskID: Int?

    private init() {
    }
}
