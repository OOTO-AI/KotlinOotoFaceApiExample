# Ooto Face Demo — Android (Kotlin, Jetpack Compose)

A **minimal demonstration** app showing how to integrate Android with **OOTO Face API** services.  
This project is for evaluation and learning only (not production).

---

## What this demo does

- **Take photo** — opens the camera and shows a preview.
- **Enroll** — sends the captured photo to create a face template.
- **Search** — performs 1:N face identification against existing templates.
- **Delete** — deletes a template by `templateId` (appears after you get one from Enroll or Search).

---

## Requirements

- Android Studio (current stable)
- Min SDK 24, Target SDK 34
- Kotlin, Jetpack Compose (Material 3)
- Networking: Retrofit + OkHttp; JSON: Moshi

---

## Setup

1. **Add API credentials** (do not hardcode):
   Create `local.properties` in the project root (next to `gradle.properties`):
   ```
   OOTO_APP_ID=your_app_id
   OOTO_APP_KEY=your_app_key
   ```
   These are injected into `BuildConfig` and attached as headers by an OkHttp interceptor.

2. **Build & run**:
   - Open in Android Studio.
   - Sync Gradle.
   - Run on a device with a camera.

---

## API endpoints used

All requests include `APP-ID` and `APP-KEY` HTTP headers.

- **Enroll** — `POST /add`
    - Query (optional): `check_liveness=true|false`, `check_deepfake=true|false`
    - Body: `multipart/form-data` with:
        - `photo`: JPEG/PNG file
        - `templateId` (optional): string
- **Search (Identify)** — `POST /identify`
    - Query (optional): `check_liveness=true|false`, `check_deepfake=true|false`
    - Body: `multipart/form-data` with:
        - `photo`: JPEG/PNG file
- **Delete** — `POST /delete`
    - Body: JSON
      ```json
      { "templateId": "<ID>" }
      ```

> The demo sends `check_liveness=false` and `check_deepfake=false` by default. Adjust in `MainViewModel`.

---

## Permissions

- `android.permission.CAMERA` — requested at runtime
- `android.permission.INTERNET` — declared in manifest

---

## Usage (UI flow)

- Launch screen shows: **Take photo**, **Enroll** (disabled), **Search** (disabled).
- Tap **Take photo** → capture → preview appears → **Enroll** and **Search** become enabled.
- Tap **Enroll** → calls `/add` → shows returned `templateId` → **Delete** appears.
- Tap **Search** → calls `/identify` → shows `templateId` + `similarity` if matched → **Delete** appears.
- Tap **Delete** → calls `/delete` with the last `templateId` → shows result.

## Error handling

- For non-2xx responses, the app attempts to parse error body as:
  ```
  { "transactionId": "...", "result": { "status": "...", "code": <int>, "info": "..." } }
  ```
- Engine code `5` is mapped to **"No faces found"** in the UI.
- If parsing fails, a generic `"Request failed"` is shown.

---

## Troubleshooting

- **401/403**: verify `OOTO_APP_ID`/`OOTO_APP_KEY` in `local.properties`.
- **Camera denied**: allow the camera permission in system settings and retry.
- **"No faces found"**: ensure the photo contains a single, clear face.
