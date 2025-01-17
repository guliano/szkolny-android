/*
 * Copyright (c) Kuba Szczodrzyński 2019-9-29.
 */

package pl.szczodrzynski.edziennik.data.api.interfaces

import com.google.gson.JsonObject
import pl.szczodrzynski.edziennik.data.db.entity.Teacher
import pl.szczodrzynski.edziennik.data.db.full.AnnouncementFull
import pl.szczodrzynski.edziennik.data.db.full.EventFull
import pl.szczodrzynski.edziennik.data.db.full.MessageFull

interface EdziennikInterface {
    fun sync(featureIds: List<Int>, viewId: Int? = null, onlyEndpoints: List<Int>? = null, arguments: JsonObject? = null)
    fun getMessage(message: MessageFull)
    fun sendMessage(recipients: List<Teacher>, subject: String, text: String)
    fun markAllAnnouncementsAsRead()
    fun getAnnouncement(announcement: AnnouncementFull)
    fun getAttachment(owner: Any, attachmentId: Long, attachmentName: String)
    fun getRecipientList()
    fun getEvent(eventFull: EventFull)
    fun firstLogin()
    fun cancel()
}
