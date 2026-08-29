# Bazcode VPN

GitHub working repository for the Bazcode-customized v2rayNG 1.6.30 Android client.

## Baseline
- Upstream project: v2rayNG 1.6.30
- `V2rayNG/app/` is replaced exactly with the current `app.zip` supplied by the project owner.
- Android 4.4.2 / API 19 compatibility is a hard requirement.
- Do not blindly upgrade Gradle, Android Gradle Plugin, Kotlin, AndroidX, or minSdk.

## Build on GitHub
Open **Actions → Build Bazcode APK → Run workflow**.

Successful runs upload:
`Bazcode-APK`

## Important workflow rule for future agents
Fix features in the repository itself and verify using GitHub Actions before providing a release.
Do not stack partial ZIP patches from older local versions.

## Current areas to verify/fix in GitHub
- Real Delay All Configuration behavior should match the intended original behavior.
- Fastest-server selection should use reliable Real Delay results.
- Subscription URL import / Update Subscription.
- Free Bazcode configuration endpoint.
- Connection stability / crash diagnostics.
- VPN sharing.
