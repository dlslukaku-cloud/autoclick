# Tự động nhấn — APK GOD V2

Native Android Kotlin auto-clicker using AccessibilityService.

## V2 features
- Tap / Double Tap / Long Press
- Swipe
- Wait
- Image Match with confidence, timeout and search region
- Infinite or finite macro loops
- Reorder / edit / delete actions
- Floating start/stop overlay
- Local-only image processing
- Android 11+

## Cloud build
1. Create a GitHub repository.
2. Upload this project with `.github/workflows/build-apk.yml` at repository root.
3. Open Actions → Build Android APK → Run workflow.
4. Download artifact `tu-dong-nhan-v2-debug-apk`.

## Important
AccessibilityService is a powerful Android capability. Enable it only for this app and disable it when not needed.
