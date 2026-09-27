# Tự động nhấn APK GOD V3

Native Android utility for macro automation.

## V3 features
- Tap / double tap / long press / swipe / wait
- Template image matching with confidence, timeout and search region
- IF Image / IF Text (OCR)
- ELSE / END IF
- LOOP / END LOOP
- STOP
- Outer macro repeat count (0 = infinite)
- Import/export macro JSON
- Floating start/stop accessibility overlay
- Local-only image processing; OCR uses ML Kit unbundled model

## Build
- Android Gradle Plugin 9.3.0
- Gradle 9.5
- JDK 17
- compileSdk 37 / targetSdk 37 / minSdk 30

GitHub Actions workflow: `.github/workflows/build-apk.yml`

## Usage
1. Build `:app:assembleDebug`.
2. Install `app-debug.apk`.
3. Open the app and enable Accessibility service.
4. Add actions and start from the floating button or the app.

## Important
AccessibilityService is a powerful Android capability. Use it only for automation you are authorized to perform. Some apps use secure windows and can prevent screenshots; OCR/template matching can therefore fail.


## Floating Editor V3.2

The AccessibilityService shows a draggable floating bubble (✦). Tap it to open quick editor actions: add a Tap at the bubble position, add Image Match via the main editor, run/stop the macro, or open the full editor. The overlay uses TYPE_ACCESSIBILITY_OVERLAY, so no SYSTEM_ALERT_WINDOW permission is required.
