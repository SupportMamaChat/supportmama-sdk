package dev.supportmama.sample

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.supportmama.SupportMama

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The whole integration: one call here, one SupportMama.open() at the button.
        SupportMama.init(
            this,
            SupportMama.Config(
                endpoint = BuildConfig.SUPPORT_ENDPOINT,
                appKey = BuildConfig.SUPPORT_APP_KEY.ifEmpty { null },
                // Optional: whatever your team would ask first.
                attributes = { mapOf("plan" to "free") },
            ),
        )
        // Optional, for push instead of background checks: hand over the FCM
        // token (SampleMessagingService passes on new ones). Firebase is only
        // set up when the build had a google-services.json.
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { SupportMama.setPushToken(this, it) }
        }
    }
}
