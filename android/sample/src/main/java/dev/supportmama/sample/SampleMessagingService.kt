package dev.supportmama.sample

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.supportmama.SupportMama

/**
 * Your app's own FCM service, with SupportMama's two lines in it. The library
 * has no Firebase dependency: it takes the token and the message data as
 * plain strings.
 */
class SampleMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = SupportMama.setPushToken(this, token)

    override fun onMessageReceived(message: RemoteMessage) {
        if (SupportMama.handlePush(this, message.data)) return
        // Your app's own pushes go on from here.
    }
}
