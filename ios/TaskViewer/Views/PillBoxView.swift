import PhotosUI
import SwiftUI
import UIKit

struct PillBoxView: View {
    @State private var selectedImage: UIImage?
    @State private var photoItem: PhotosPickerItem?
    @State private var showingCamera = false
    @State private var isAnalyzing = false
    @State private var result: String?
    @State private var errorMessage: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Group {
                    if let selectedImage {
                        Image(uiImage: selectedImage)
                            .resizable()
                            .scaledToFit()
                    } else {
                        VStack(spacing: 12) {
                            Image(systemName: "pills.circle")
                                .font(.system(size: 48))
                                .foregroundStyle(.secondary)
                            Text("No Image Selected")
                                .font(.headline)
                            Text("Take a photo or choose one from your library.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 260)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))

                HStack {
                    Button {
                        showingCamera = true
                    } label: {
                        Label("Camera", systemImage: "camera")
                    }
                    .buttonStyle(.bordered)
                    .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))

                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Label("Photos", systemImage: "photo")
                    }
                    .buttonStyle(.bordered)
                }

                Button {
                    analyze()
                } label: {
                    if isAnalyzing {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        Label("Analyze Pill Box", systemImage: "sparkles")
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(selectedImage == nil || isAnalyzing)

                if let result {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("💊 Analysis Results")
                            .font(.headline)
                        Text(result)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding()
                    .background(Color.yellow.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                }
            }
            .padding()
        }
        .navigationTitle("Pill Box")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingCamera) {
            ImagePicker(sourceType: .camera) { image in
                selectedImage = image
                result = nil
            }
        }
        .onChange(of: photoItem) { newItem in
            guard let newItem else { return }
            Task {
                do {
                    guard let data = try await newItem.loadTransferable(type: Data.self),
                          let image = UIImage(data: data) else {
                        throw GeminiServiceError.invalidImage
                    }
                    selectedImage = image
                    result = nil
                } catch {
                    errorMessage = error.localizedDescription
                }
            }
        }
        .alert(
            "Analysis Failed",
            isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )
        ) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func analyze() {
        guard let selectedImage else { return }
        isAnalyzing = true
        result = nil
        Task {
            do {
                result = try await GeminiService.analyzePillBox(selectedImage)
            } catch {
                errorMessage = error.localizedDescription
            }
            isAnalyzing = false
        }
    }
}
