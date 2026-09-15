# Sonny visitor SDK for Android

Add customer support chat to an Android app using Sonny's existing widget. The
SDK provides modal and embedded chat, encrypted visitor persistence, native file
picking, external links, identity, unread events, and push-token registration.
Android API 24 or later is required. This is separate from the Sonny agent inbox app.

**Preview:** the hosted mobile rollout and push delivery are still being completed.
No stable Maven Central release is available yet. Pin a reviewed Git commit when
trying the SDK; an app key from the mobile-enabled Sonny deployment is required.

## Try the sample

Clone this repository and open it in Android Studio. Use JDK 21 and Android SDK 36,
then run the `sample` app. Enter the Sonny origin, website site ID and an Android
app key created in **Channels → Live Chat → Mobile apps**.

```sh
./gradlew :sdk:testDebugUnitTest :sdk:assembleRelease :sample:assembleDebug
```

The sample's application ID is `com.usesonny.sample`. The SDK does not ask for
notification permission. Your app owns Firebase configuration and notification
permission and passes its FCM token to Sonny. No Firebase project or provider
credentials are bundled in this sample.

## Use from source

Until a Maven Central release is available, clone this repository beside your app
and add a composite build to your app's `settings.gradle.kts`:

```kotlin
includeBuild("../sonny-android-sdk") {
    dependencySubstitution {
        substitute(module("com.usesonny:sonny-sdk")).using(project(":sdk"))
    }
}
```

Then add `implementation("com.usesonny:sonny-sdk:1.0.0")` to the app module's
dependencies. Gradle resolves that coordinate from the source checkout above.

```kotlin
import com.usesonny.sdk.Sonny

// Application.onCreate, on the main thread:
Sonny.configure(this, "YOUR_SITE_ID", "YOUR_ANDROID_APP_KEY")

// Activity:
Sonny.present(this)
Sonny.identify(jwt = tokenFromYourBackend)
Sonny.setAttributes(mapOf("plan" to "pro"))
val subscription = Sonny.addListener { event ->
    if (event.name == "unread") updateBadge(Sonny.unreadCount)
}
// Close the subscription when its owner is destroyed.
subscription.close()
Sonny.reset() // At app logout.
```

For embedded chat, add `SonnyChatView(activity)` to a view hierarchy owned by an
AndroidX `ComponentActivity`. Modal and embedded chat share one WebView; keep only
one active chat presentation. Configure once per process before creating a view.

## Identity and notifications

The site ID and app key are public configuration. Authenticate customers with a
short-lived identity JWT signed by your backend; never embed the signing secret.
Listen for `identityRequired` and supply a fresh JWT with `identify`.

Call `registerPushToken(token)` after your app gets an FCM token, and
`handlePush(activity, payload)` when the user taps a notification. Other apps'
payloads and pushes for a logged-out visitor are rejected. `reset()` rotates the
visitor ID and queues token revocation; offline revocation completes after the
SDK reconnects. Real customer-provider delivery remains unverified in this preview.

## Help and contributions

- [Documentation](https://www.usesonny.com/docs/widget-mobile)
- [Report a bug or request a feature](https://github.com/usesonny/sonny-android-sdk/issues)
- [iOS SDK](https://github.com/usesonny/sonny-ios-sdk)
- [React Native SDK](https://github.com/usesonny/sonny-react-native-sdk)
- [Contributing](CONTRIBUTING.md) · [Security reports](SECURITY.md) · [MIT license](LICENSE)
