package com.example.helixapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.helixapp.ui.theme.HelixAccent
import com.example.helixapp.ui.theme.HelixBorder
import com.example.helixapp.ui.theme.HelixSurfaceRaised
import org.json.JSONObject
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AdminUsersScreen(
    onBack: () -> Unit,
    viewModel: AdminUsersViewModel = helixViewModel { AdminUsersViewModel(it) },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val loading = state.loading
    val saving = state.saving
    val status = state.status
    val users = state.users

    var newUsername by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf("user") }

    fun updateUser(user: AdminUser, patch: JSONObject) = viewModel.updateUser(user, patch)

    fun createUser() = viewModel.createUser(newUsername, newPassword, newRole) {
        newUsername = ""
        newPassword = ""
        newRole = "user"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Users", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Helix administration",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (loading) {
            CircularProgressIndicator()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Create user", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Create another account that can sign in to this Helix server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = newUsername,
                    onValueChange = { newUsername = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Temporary password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("At least 8 characters") },
                )

                Text("Role", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AdminRoleChoice(
                        label = "User",
                        selected = newRole == "user",
                        modifier = Modifier.weight(1f),
                        onClick = { newRole = "user" },
                    )
                    AdminRoleChoice(
                        label = "Administrator",
                        selected = newRole == "admin",
                        modifier = Modifier.weight(1f),
                        onClick = { newRole = "admin" },
                    )
                }

                Button(
                    onClick = { createUser() },
                    enabled = !saving && newUsername.trim().length >= 3 && newPassword.length >= 8,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (saving) "Working…" else "Create user")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Existing users", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${users.size} ${if (users.size == 1) "account" else "accounts"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                users.forEach { user ->
                    AdminUserCard(
                        user = user,
                        disabled = saving,
                        onRoleChange = { role ->
                            updateUser(user, JSONObject().put("role", role))
                        },
                        onActiveChange = { active ->
                            updateUser(user, JSONObject().put("is_active", active))
                        },
                        onSubsonicChange = { allowed ->
                            updateUser(
                                user,
                                JSONObject().put("subsonic_import_override", allowed),
                            )
                        },
                    )
                }
            }
        }

        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status == "Saved" || status.startsWith("Created ")) {
                    HelixAccent
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun AdminUserCard(
    user: AdminUser,
    disabled: Boolean,
    onRoleChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit,
    onSubsonicChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HelixSurfaceRaised,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, HelixBorder),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(user.username, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (user.role == "admin") "Administrator" else "User",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (user.role == "admin") HelixAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (user.isActive) "Active" else "Disabled",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (user.isActive) HelixAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Role", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AdminRoleChoice(
                    label = "User",
                    selected = user.role == "user",
                    enabled = !disabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onRoleChange("user") },
                )
                AdminRoleChoice(
                    label = "Administrator",
                    selected = user.role == "admin",
                    enabled = !disabled,
                    modifier = Modifier.weight(1f),
                    onClick = { onRoleChange("admin") },
                )
            }

            AdminToggleRow(
                title = "Account active",
                subtitle = "Allow this user to sign in",
                checked = user.isActive,
                enabled = !disabled,
                onCheckedChange = onActiveChange,
            )

            if (user.role == "admin") {
                Text(
                    "Subsonic import: always allowed for administrators",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AdminToggleRow(
                    title = "Allow Subsonic import",
                    subtitle = "Let this user add discovered music to the library",
                    checked = user.subsonicImportOverride,
                    enabled = !disabled,
                    onCheckedChange = onSubsonicChange,
                )
            }
        }
    }
}

@Composable
private fun AdminToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun AdminRoleChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.then(
            if (enabled) Modifier.clickable(onClick = onClick) else Modifier
        ),
        color = if (selected) HelixSurfaceRaised else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) HelixAccent else HelixBorder),
    ) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 11.dp, horizontal = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                selected -> HelixAccent
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
