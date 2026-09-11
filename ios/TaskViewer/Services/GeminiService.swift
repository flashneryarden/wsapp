import Foundation
import UIKit

enum GeminiServiceError: LocalizedError {
    case missingAPIKey
    case invalidImage
    case invalidResponse
    case requestFailed(String)

    var errorDescription: String? {
        switch self {
        case .missingAPIKey: "Add GEMINI_API_KEY to Config.xcconfig."
        case .invalidImage: "The selected image could not be prepared."
        case .invalidResponse: "Gemini returned an unexpected response."
        case let .requestFailed(message): message
        }
    }
}

enum GeminiService {
    private static let models = [
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
        "gemini-2.0-flash-lite",
    ]

    static func analyzePillBox(_ image: UIImage) async throws -> String {
        guard let apiKey = Bundle.main.object(forInfoDictionaryKey: "GEMINI_API_KEY") as? String,
              !apiKey.isEmpty,
              apiKey != "$(GEMINI_API_KEY)" else {
            throw GeminiServiceError.missingAPIKey
        }
        guard let imageData = resizedJPEG(image) else {
            throw GeminiServiceError.invalidImage
        }

        let prompt = """
        This is a round pill organizer with 7 compartments in a circle.
        Look carefully at each individual compartment.
        Which ones have pills and which are empty?
        List each one with ✅ for full or ❌ for empty.
        End with a count: X full, Y empty.
        """
        let body: [String: Any] = [
            "contents": [[
                "parts": [
                    ["text": prompt],
                    ["inline_data": [
                        "mime_type": "image/jpeg",
                        "data": imageData.base64EncodedString(),
                    ]],
                ],
            ]],
        ]
        let encodedBody = try JSONSerialization.data(withJSONObject: body)
        var lastError: Error = GeminiServiceError.invalidResponse

        for model in models {
            for attempt in 0..<2 {
                do {
                    guard let url = URL(
                        string: "https://generativelanguage.googleapis.com/v1beta/models/\(model):generateContent?key=\(apiKey)"
                    ) else {
                        throw GeminiServiceError.invalidResponse
                    }
                    var request = URLRequest(url: url)
                    request.httpMethod = "POST"
                    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    request.httpBody = encodedBody
                    request.timeoutInterval = 60

                    let (data, response) = try await URLSession.shared.data(for: request)
                    guard let http = response as? HTTPURLResponse else {
                        throw GeminiServiceError.invalidResponse
                    }
                    if http.statusCode == 429 || http.statusCode == 503 {
                        lastError = GeminiServiceError.requestFailed("\(model) returned \(http.statusCode)")
                        if attempt == 0 {
                            try await Task.sleep(for: .seconds(2))
                        }
                        continue
                    }
                    guard (200..<300).contains(http.statusCode) else {
                        let message = String(data: data, encoding: .utf8) ?? "Unknown error"
                        throw GeminiServiceError.requestFailed("\(model) returned \(http.statusCode): \(message)")
                    }

                    guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                          let candidates = json["candidates"] as? [[String: Any]],
                          let content = candidates.first?["content"] as? [String: Any],
                          let parts = content["parts"] as? [[String: Any]],
                          let text = parts.first?["text"] as? String else {
                        throw GeminiServiceError.invalidResponse
                    }
                    return text
                } catch {
                    lastError = error
                    if attempt == 0 {
                        try? await Task.sleep(for: .seconds(2))
                    }
                }
            }
        }

        throw lastError
    }

    private static func resizedJPEG(_ image: UIImage) -> Data? {
        let maximumSize: CGFloat = 2048
        let scale = min(1, maximumSize / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: size)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        return resized.jpegData(compressionQuality: 0.95)
    }
}
