package com.example.helixapp.data

import android.content.Context
import com.example.helixapp.AccountInfo
import com.example.helixapp.AdminUser
import com.example.helixapp.PlaybackSettings
import com.example.helixapp.parseAccountInfo
import com.example.helixapp.parseAdminUser
import com.example.helixapp.parseAdminUsers
import com.example.helixapp.parsePlaybackSettings
import org.json.JSONObject

/** The signed-in account, its shared settings, and (for admins) user management. */
interface AccountRepository {
    suspend fun me(): AccountInfo
    suspend fun logout()
    suspend fun playbackSettings(): PlaybackSettings
    suspend fun updateSetting(key: String, value: Any)
    suspend fun users(): List<AdminUser>
    /** Apply [patch] (e.g. {"role": "admin"}) and return the updated user. */
    suspend fun updateUser(userId: String, patch: JSONObject): AdminUser
    suspend fun createUser(username: String, password: String, role: String): AdminUser
}

class HelixAccountRepository(context: Context) : AccountRepository {
    private val ctx = context.applicationContext

    override suspend fun me(): AccountInfo = parseAccountInfo(helixCall(ctx) { it.me() })

    override suspend fun logout() {
        helixCall(ctx) { it.logout() }
    }

    override suspend fun playbackSettings(): PlaybackSettings = parsePlaybackSettings(helixCall(ctx) { it.userSettings() })

    override suspend fun updateSetting(key: String, value: Any) {
        helixCall(ctx) { it.updateUserSettings(JSONObject().put(key, value).toJsonBody()) }
    }

    override suspend fun users(): List<AdminUser> = parseAdminUsers(helixCall(ctx) { it.adminUsers() })

    override suspend fun updateUser(userId: String, patch: JSONObject): AdminUser =
        parseAdminUser(JSONObject(helixCall(ctx) { it.adminUpdateUser(userId, patch.toJsonBody()) }))

    override suspend fun createUser(username: String, password: String, role: String): AdminUser {
        val body = JSONObject().put("username", username).put("password", password).put("role", role)
        return parseAdminUser(JSONObject(helixCall(ctx) { it.adminCreateUser(body.toJsonBody()) }))
    }
}
