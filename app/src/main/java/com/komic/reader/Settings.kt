package com.komic.reader

import android.content.SharedPreferences
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Credentials
import okio.ByteString.Companion.decodeBase64
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.net.URI
import java.util.UUID

internal data class Server(val id: String, val name: String, val address: String)
internal fun servers(prefs: SharedPreferences): List<Server> {
    val list = JSONArray(prefs.getString("servers", "[]"))
    return List(list.length()) {
        val item = list.getJSONObject(it)
        Server(item.getString("id"), item.getString("name"), item.getString("url"))
    }
}

private val languageOptions = mapOf(
    "" to R.string.follow_system_language,
    "zh-Hans" to R.string.language_chinese,
    "en" to R.string.language_english
)

@Composable
fun Choices(values: List<String>, selected: String, active: Color, foreground: Color, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            val checked = selected == value
            val shape = RoundedCornerShape(5.dp)
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(shape)
                .selectable(checked, role = Role.RadioButton) { onChange(value) }
                .padding(vertical = 4.dp).border(1.dp, if (checked) active else foreground.copy(alpha = .4f), shape)
                .background(if (checked) active else Color.Transparent, shape).padding(vertical = 7.dp),
                contentAlignment = Alignment.Center) {
                Text(androidx.compose.ui.res.stringResource(optionLabel(value)), color = if (checked && active == Wine) MaterialTheme.colorScheme.onPrimary else if (checked) Color.White else foreground, fontSize = 14.sp, fontFamily = Editorial)
            }
        }
    }
}

@Composable
internal fun Settings(prefs: SharedPreferences, theme: String, onTheme: (String) -> Unit, language: String, onLanguage: (String) -> Unit, openComic: (Server, DavItem) -> Unit, importComics: (Server, List<DavItem>) -> Unit, importing: Boolean) {
    var entries by remember { mutableStateOf(servers(prefs)) }
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    entries.find { it.id == browsing }?.let { server ->
        RemoteDirectory(server, openComic, importComics, importing) { browsing = null }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun store(next: List<Server>) {
        entries.filter { old -> next.none { it.id == old.id } }
            .forEach { DavCredentials(context).forget(it) }
        val json = JSONArray()
        next.forEach { json.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.address)) }
        prefs.edit().putString("servers", json.toString()).apply()
        entries = next
        edit = null
    }
    Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().padding(18.dp)) {
        Section(context.getString(R.string.network_libraries))
        Text("WebDAV", Modifier.padding(top = 13.dp, bottom = 8.dp), fontFamily = Editorial,
            fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        entries.forEach { server ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).border(1.dp, Line, RoundedCornerShape(7.dp))
                .clickable { browsing = server.id }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storage, null, tint = Wine)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(server.name, fontFamily = Editorial, fontWeight = FontWeight.Bold)
                    Text(server.address, fontSize = 13.sp, color = Ink)
                }
                IconButton(onClick = { edit = server.id }) { Icon(Icons.Outlined.Edit, context.getString(R.string.edit_address), tint = Wine) }
            }
        }
        TextButton(onClick = { edit = "new" }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(context.getString(R.string.add_address)) }
        Spacer(Modifier.height(27.dp))
        Section(context.getString(R.string.appearance))
        Text(context.getString(R.string.app_theme), Modifier.padding(top = 16.dp, bottom = 8.dp), fontFamily = Editorial, fontWeight = FontWeight.SemiBold)
        Choices(listOf("深色", "浅色", "跟随系统"), theme, Wine, Ink, onChange = onTheme)
        Text(context.getString(R.string.app_language), Modifier.padding(top = 16.dp, bottom = 8.dp),
            fontFamily = Editorial, fontWeight = FontWeight.SemiBold)
        var selectedLanguage by remember(language) { mutableStateOf(language) }
        var languageMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { languageMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(context.getString(languageOptions[selectedLanguage] ?: R.string.follow_system_language), Modifier.weight(1f))
                Icon(Icons.Outlined.ArrowDropDown, null)
            }
            DropdownMenu(expanded = languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
                languageOptions.forEach { (tag, label) ->
                    DropdownMenuItem(
                        text = { Text(context.getString(label)) },
                        trailingIcon = { if (tag == selectedLanguage) Icon(Icons.Outlined.Check, null) },
                        onClick = {
                            languageMenuOpen = false
                            selectedLanguage = tag
                            onLanguage(tag)
                        }
                    )
                }
            }
        }
    }
    edit?.let { id ->
        val original = entries.find { it.id == id }
        var name by rememberSaveable(id) { mutableStateOf(original?.name.orEmpty()) }
        var url by rememberSaveable(id) { mutableStateOf(original?.address.orEmpty()) }
        val sameAddress = original != null && original.address == url.trim()
        val saved = remember(id, sameAddress) {
            if (sameAddress) DavCredentials(context).load(requireNotNull(original))
                ?.removePrefix("Basic ")?.decodeBase64()?.utf8() else null
        }
        var username by remember(id, sameAddress) { mutableStateOf(saved?.substringBefore(':').orEmpty()) }
        var password by remember(id, sameAddress) { mutableStateOf(saved?.substringAfter(':').orEmpty()) }
        var error by rememberSaveable(id) { mutableStateOf("") }
        var deleting by rememberSaveable(id) { mutableStateOf(false) }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(context.getString(R.string.remove_address_prompt)) },
            text = { Text(original?.name.orEmpty()) }, confirmButton = { TextButton(onClick = { store(entries.filterNot { it.id == id }) }) { Text(context.getString(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(context.getString(R.string.cancel)) } })
        else AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (original == null) context.getString(R.string.new_webdav) else context.getString(R.string.edit_webdav), fontFamily = Editorial) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(context.getString(R.string.library_name)) }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text(context.getString(R.string.server_address)) }, placeholder = { Text("https://nas.example.com/dav") }, singleLine = true)
                OutlinedTextField(username, { username = it }, label = { Text(context.getString(R.string.username)) }, singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text(context.getString(R.string.password)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (error.isNotEmpty()) Text(error, color = Wine)
                if (original != null) TextButton(onClick = { deleting = true }) { Text(context.getString(R.string.remove_address)) }
            } }, confirmButton = { TextButton(onClick = {
                val uri = runCatching { URI(url.trim()) }.getOrNull()
                if (name.isBlank() || uri?.scheme?.lowercase() !in listOf("https","http") || uri?.host.isNullOrBlank() || uri?.userInfo != null) {
                    error = context.getString(R.string.invalid_address)
                } else if ((password.isNotEmpty() && username.isBlank()) || ':' in username) {
                    error = context.getString(R.string.invalid_username)
                } else {
                    val updated = Server(original?.id ?: UUID.randomUUID().toString(), name.trim(), url.trim())
                    val credentials = DavCredentials(context)
                    try {
                        if (username.isBlank()) credentials.forget(updated)
                        else credentials.save(updated, Credentials.basic(username, password, Charsets.UTF_8))
                    } catch (_: Exception) {
                        error = context.getString(R.string.credentials_save_failed)
                        return@TextButton
                    }
                    store(if (original == null) entries + updated else entries.map { if (it.id == id) updated else it })
                }
            }) { Text(context.getString(R.string.save)) } }, dismissButton = { TextButton(onClick = { edit = null }) { Text(context.getString(R.string.cancel)) } })
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontFamily = Editorial, fontWeight = FontWeight.Bold, color = Wine, fontSize = 22.sp)
    HorizontalDivider(Modifier.padding(top = 7.dp), color = Wine.copy(alpha = .6f))
}

private fun optionLabel(key: String): Int = when (key) {
    "深色" -> R.string.dark
    "浅色" -> R.string.light
    "跟随系统" -> R.string.system
    "整页" -> R.string.fit_page
    "适宽" -> R.string.fit_width
    "原大" -> R.string.original
    "连续滚动" -> R.string.continuous
    "纵向分页" -> R.string.vertical
    "左右" -> R.string.horizontal
    "竖屏" -> R.string.portrait
    "横屏" -> R.string.landscape
    "自动" -> R.string.auto_rotate
    else -> error("Unknown option: $key")
}
