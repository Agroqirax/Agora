package com.newoether.agora.tool

import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.provider.ContactsContract
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put

/**
 * Tool for reading and writing device contacts via the platform [ContactsContract]
 * content provider. No Google Play Services dependency — works against whatever
 * accounts/sync adapters are registered on the device (local, Google, CardDAV via
 * DAVx5, etc.), consistent with the location/calendar tools.
 *
 * Reads (`search_contacts`, `get_contact`) are never gated beyond the runtime
 * permission check. Writes (`create_contact`, `update_contact`, `delete_contact`)
 * additionally go through [confirmWrite], a no-op when contacts_confirm_enabled
 * is off.
 */
class ContactsToolProvider(private val app: Application) : ToolProvider {

    var confirmWrite: (suspend (summary: String) -> Boolean)? = null
    var requestPermission: (suspend () -> Boolean)? = null

    private val resolver get() = app.contentResolver

    private val toolNames = setOf(
        "search_contacts", "get_contact",
        "create_contact", "update_contact", "delete_contact"
    )

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!ctx.contactsEnabled) return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "search_contacts",
                description = "Search contacts by name, phone number, or email substring.",
                parameters = ToolParameters(
                    properties = mapOf("query" to ToolProperty("string", "Text to search for.")),
                    required = listOf("query")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "get_contact",
                description = "Get full details (all phone numbers, emails) for one contact.",
                parameters = ToolParameters(
                    properties = mapOf("contact_id" to ToolProperty("string", "Id of the contact (see search_contacts).")),
                    required = listOf("contact_id")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "create_contact",
                description = "Create a new contact. Asks the user to confirm before creating.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "name" to ToolProperty("string", "Full display name."),
                        "phone" to ToolProperty("string", "Phone number (optional)."),
                        "email" to ToolProperty("string", "Email address (optional).")
                    ),
                    required = listOf("name")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "update_contact",
                description = "Update a contact's name, phone, or email. Only provided fields are changed (phone/email are replaced entirely, not merged). Asks the user to confirm before applying.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "contact_id" to ToolProperty("string", "Id of the contact to update (see search_contacts)."),
                        "name" to ToolProperty("string", "New display name (optional)."),
                        "phone" to ToolProperty("string", "New phone number, replaces the first existing one (optional)."),
                        "email" to ToolProperty("string", "New email address, replaces the first existing one (optional).")
                    ),
                    required = listOf("contact_id")
                )
            )),
            ToolDefinition(function = ToolFunction(
                name = "delete_contact",
                description = "Delete a contact. Asks the user to confirm before deleting.",
                parameters = ToolParameters(
                    properties = mapOf("contact_id" to ToolProperty("string", "Id of the contact to delete (see search_contacts).")),
                    required = listOf("contact_id")
                )
            ))
        )
    }

    override fun handles(name: String): Boolean = name in toolNames

    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String {
        if (!ctx.contactsEnabled) return err("disabled", "The contacts tool is disabled in settings.")

        if (!hasContactsPermission()) {
            val granted = requestPermission?.invoke() ?: false
            if (!granted || !hasContactsPermission()) {
                return err("permission_denied", "Contacts permission was not granted.")
            }
        }

        val args = parseToolArgs(arguments)
        return try {
            when (name) {
                "search_contacts" -> searchContacts(args)
                "get_contact" -> getContact(args)
                "create_contact" -> createContact(args)
                "update_contact" -> updateContact(args)
                "delete_contact" -> deleteContact(args)
                else -> err("unknown_tool", "Unknown tool: $name")
            }
        } catch (e: SecurityException) {
            err("permission_denied", e.message)
        } catch (e: Exception) {
            DebugLog.e("ContactsTool", "$name failed", e)
            err("contacts_error", e.message)
        }
    }

    private fun hasContactsPermission() = hasPermission(android.Manifest.permission.READ_CONTACTS)
    private fun hasWritePermission() = hasPermission(android.Manifest.permission.WRITE_CONTACTS)

    private fun hasPermission(perm: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(app, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // ── search_contacts ─────────────────────────────────────

    private suspend fun searchContacts(args: Map<String, JsonElement>): String = withContext(Dispatchers.IO) {
        val query = arg(args, "query")
        if (query.isBlank()) return@withContext err("invalid_argument", "query is required")

        val uri = android.net.Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, android.net.Uri.encode(query)
        )
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME
        )
        val seen = LinkedHashSet<Long>()
        val results = mutableListOf<kotlinx.serialization.json.JsonObject>()

        // Phone/email filter URIs return Data-table rows keyed by contact id; de-dup by contact.
        resolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (seen.add(id)) results.add(buildJsonObject { put("contact_id", id.toString()); put("name", c.getString(1) ?: "") })
            }
        }
        // Also search plain contact display names (phone-number filter URI above won't
        // match a name search like "Jane").
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI, projection,
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (seen.add(id)) results.add(buildJsonObject { put("contact_id", id.toString()); put("name", c.getString(1) ?: "") })
            }
        }

        buildJsonObject {
            put("type", "search_contacts")
            putJsonArray("contacts") { results.forEach { add(it) } }
        }.toString()
    }

    // ── get_contact ──────────────────────────────────────────

    private suspend fun getContact(args: Map<String, JsonElement>): String = withContext(Dispatchers.IO) {
        val contactId = arg(args, "contact_id").toLongOrNull()
            ?: return@withContext err("invalid_argument", "contact_id is required and must be numeric.")

        val name = fetchDisplayName(contactId) ?: return@withContext err("not_found", "No contact with id $contactId.")
        val phones = mutableListOf<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()), null
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { phones.add(it) } }

        val emails = mutableListOf<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(contactId.toString()), null
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { emails.add(it) } }

        buildJsonObject {
            put("type", "get_contact")
            put("contact_id", contactId.toString())
            put("name", name)
            putJsonArray("phones") { phones.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("emails") { emails.forEach { add(JsonPrimitive(it)) } }
        }.toString()
    }

    private fun fetchDisplayName(contactId: Long): String? {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        resolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    // ── create_contact ──────────────────────────────────────

    private suspend fun createContact(args: Map<String, JsonElement>): String {
        val name = arg(args, "name")
        if (name.isBlank()) return err("invalid_argument", "name is required")
        val phone = arg(args, "phone")
        val email = arg(args, "email")

        if (!hasWritePermission()) return err("permission_denied", "Write-contacts permission was not granted.")
        if (confirmWrite?.invoke("Create contact \"$name\"") == false) {
            return err("user_denied", "The user declined to create this contact.")
        }

        return withContext(Dispatchers.IO) {
            val ops = arrayListOf<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )
            if (phone.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
            }
            if (email.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                        .build()
                )
            }

            val results = try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            } catch (e: Exception) {
                return@withContext err("write_failed", "Could not create the contact: ${e.message}")
            }
            val rawContactUri = results.firstOrNull()?.uri
                ?: return@withContext err("write_failed", "Could not create the contact.")
            val rawContactId = ContentUris.parseId(rawContactUri)
            val contactId = resolveContactIdFromRaw(rawContactId)
            buildJsonObject {
                put("type", "create_contact")
                put("contact_id", (contactId ?: rawContactId).toString())
                put("ok", true)
            }.toString()
        }
    }

    private fun resolveContactIdFromRaw(rawContactId: Long): Long? {
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?", arrayOf(rawContactId.toString()), null
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    // ── update_contact ───────────────────────────────────────

    private suspend fun updateContact(args: Map<String, JsonElement>): String {
        val contactId = arg(args, "contact_id").toLongOrNull()
            ?: return err("invalid_argument", "contact_id is required and must be numeric.")
        val currentName = withContext(Dispatchers.IO) { fetchDisplayName(contactId) }
            ?: return err("not_found", "No contact with id $contactId.")

        val newName = if (args.containsKey("name")) arg(args, "name").ifBlank { null } else null
        val newPhone = if (args.containsKey("phone")) arg(args, "phone") else null
        val newEmail = if (args.containsKey("email")) arg(args, "email") else null

        val changes = buildList {
            if (newName != null) add("name -> \"$newName\"")
            if (newPhone != null) add("phone -> \"$newPhone\"")
            if (newEmail != null) add("email -> \"$newEmail\"")
        }
        if (changes.isEmpty()) return err("invalid_argument", "No fields to update were provided.")

        if (!hasWritePermission()) return err("permission_denied", "Write-contacts permission was not granted.")
        if (confirmWrite?.invoke("Update \"$currentName\": ${changes.joinToString(", ")}") == false) {
            return err("user_denied", "The user declined to update this contact.")
        }

        return withContext(Dispatchers.IO) {
            val rawContactId = fetchRawContactId(contactId)
                ?: return@withContext err("not_found", "No editable raw contact found for id $contactId.")
            val ops = arrayListOf<ContentProviderOperation>()

            if (newName != null) {
                ops.add(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection(
                            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                            arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        )
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, newName)
                        .build()
                )
            }
            if (newPhone != null) upsertOrReplaceSingleValue(
                ops, rawContactId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER, newPhone
            )
            if (newEmail != null) upsertOrReplaceSingleValue(
                ops, rawContactId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS, newEmail
            )

            try {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            } catch (e: Exception) {
                return@withContext err("write_failed", "Could not update the contact: ${e.message}")
            }
            buildJsonObject { put("type", "update_contact"); put("contact_id", contactId.toString()); put("ok", true) }.toString()
        }
    }

    /** Replaces the first Data row of [mimeType] for this raw contact with [value], or
     *  inserts one if none exists yet. Deliberately single-value (not multi-value merge) —
     *  matches the tool description ("replaces the first existing one"). */
    private fun upsertOrReplaceSingleValue(
        ops: ArrayList<ContentProviderOperation>, rawContactId: Long, mimeType: String, column: String, value: String
    ) {
        val existing = resolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), mimeType), null
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

        if (existing != null) {
            ops.add(
                ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(existing.toString()))
                    .withValue(column, value)
                    .build()
            )
        } else {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, mimeType)
                    .withValue(column, value)
                    .build()
            )
        }
    }

    private fun fetchRawContactId(contactId: Long): Long? {
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(contactId.toString()), null
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    // ── delete_contact ───────────────────────────────────────

    private suspend fun deleteContact(args: Map<String, JsonElement>): String {
        val contactId = arg(args, "contact_id").toLongOrNull()
            ?: return err("invalid_argument", "contact_id is required and must be numeric.")
        val name = withContext(Dispatchers.IO) { fetchDisplayName(contactId) }
            ?: return err("not_found", "No contact with id $contactId.")

        if (!hasWritePermission()) return err("permission_denied", "Write-contacts permission was not granted.")
        if (confirmWrite?.invoke("Delete contact \"$name\"") == false) {
            return err("user_denied", "The user declined to delete this contact.")
        }

        return withContext(Dispatchers.IO) {
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            val rows = resolver.delete(uri, null, null)
            if (rows == 0) return@withContext err("write_failed", "Could not delete the contact.")
            buildJsonObject { put("type", "delete_contact"); put("contact_id", contactId.toString()); put("ok", true) }.toString()
        }
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun parseToolArgs(arguments: String): Map<String, JsonElement> = try {
        kotlinx.serialization.json.Json.decodeFromString<Map<String, JsonElement>>(arguments.ifBlank { "{}" })
    } catch (_: Exception) { emptyMap() }

    private fun arg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content ?: ""

    private fun err(code: String, message: String?): String = buildJsonObject {
        put("type", "contacts")
        put("error", code)
        if (!message.isNullOrBlank()) put("message", message)
    }.toString()
}
