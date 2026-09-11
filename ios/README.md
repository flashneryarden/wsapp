# Task Viewer for iPhone

This folder contains a native SwiftUI port of the Android companion app. It uses
the same Firestore collections and task schema as the Android and Node.js apps.

## Included features

- Real-time Firestore task list
- Critical-first, due-date, newest, group, sender, and category sorting
- Status, group, sender, and category filters
- Create, complete, reopen, edit, and delete tasks
- Notes, due dates, categories, and skipped groups
- Delete-old and delete-all actions
- Critical-task lock-screen notifications through Firebase Cloud Messaging/APNs
- Camera and photo-library pill-box analysis through Gemini

## Requirements

- A Mac running a current version of Xcode
- A free or paid Apple developer account for installing on a physical iPhone
- [XcodeGen](https://github.com/yonaskolb/XcodeGen)
- Access to the existing Firebase project
- The updated Node.js backend running on the cloud PC

## 1. Register the iOS app in Firebase

1. Open the Firebase console for the project used by the Android app.
2. Add an iOS app with bundle ID `com.wsapp.taskviewer`.
3. Download `GoogleService-Info.plist`.
4. Place it at:

   `ios/TaskViewer/Resources/GoogleService-Info.plist`

5. In Firebase **Project settings → Cloud Messaging**, upload an APNs
   authentication key from your Apple Developer account.

## 2. Configure signing and Gemini

Copy `Config.example.xcconfig` to `Local.xcconfig` and fill in:

```text
GEMINI_API_KEY = your_key
DEVELOPMENT_TEAM = your_apple_team_id
```

`Local.xcconfig` and `GoogleService-Info.plist` are ignored by Git so credentials
are not committed.

## 3. Generate and open the Xcode project

From Terminal on the Mac:

```bash
cd /path/to/wsapp/ios
brew install xcodegen
xcodegen generate
open TaskViewer.xcodeproj
```

In Xcode:

1. Select the **TaskViewer** target.
2. Confirm the correct development team under **Signing & Capabilities**.
3. Ensure **Push Notifications** and **Time Sensitive Notifications** appear.
4. Select the connected iPhone as the run destination.
5. Press **Run**.

Open the app once and allow notifications. The app registers its FCM token in
the Firestore `devices` collection and also subscribes to the `critical_tasks`
fallback topic.

## Distribution

An iPhone app cannot be compiled or signed from Windows. Use Xcode on a Mac to
install it directly on your phone, archive an `.ipa` for an eligible device, or
upload a signed archive to TestFlight.
