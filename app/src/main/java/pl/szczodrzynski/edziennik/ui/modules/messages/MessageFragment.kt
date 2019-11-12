/*
 * Copyright (c) Kuba Szczodrzyński 2019-11-12.
 */

package pl.szczodrzynski.edziennik.ui.modules.messages

import android.os.Bundle
import android.os.Environment
import android.text.Html
import android.text.TextUtils
import android.view.Gravity.CENTER_VERTICAL
import android.view.Gravity.END
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.mikepenz.iconics.IconicsColor
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.IconicsSize
import com.mikepenz.iconics.typeface.IIcon
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import com.mikepenz.iconics.utils.sizeDp
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.MainActivity
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.api.v2.events.MessageGetEvent
import pl.szczodrzynski.edziennik.api.v2.events.task.EdziennikTask
import pl.szczodrzynski.edziennik.data.db.modules.messages.Message.TYPE_SENT
import pl.szczodrzynski.edziennik.data.db.modules.messages.MessageFull
import pl.szczodrzynski.edziennik.databinding.MessageFragmentBinding
import pl.szczodrzynski.edziennik.onClick
import pl.szczodrzynski.edziennik.utils.Anim
import pl.szczodrzynski.edziennik.utils.Themes
import pl.szczodrzynski.edziennik.utils.Utils
import pl.szczodrzynski.edziennik.utils.Utils.getStringFromFile
import pl.szczodrzynski.edziennik.utils.Utils.readableFileSize
import pl.szczodrzynski.edziennik.utils.models.Date
import pl.szczodrzynski.edziennik.utils.models.Time
import pl.szczodrzynski.navlib.colorAttr
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.math.min

class MessageFragment : Fragment(), CoroutineScope {
    companion object {
        private const val TAG = "MessageFragment"
    }

    private lateinit var app: App
    private lateinit var activity: MainActivity
    private lateinit var b: MessageFragmentBinding

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main

    private lateinit var message: MessageFull
    private var attachmentList = mutableListOf<Attachment>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        activity = (getActivity() as MainActivity?) ?: return null
        context ?: return null
        app = activity.application as App
        context!!.theme.applyStyle(Themes.appTheme, true)
        b = MessageFragmentBinding.inflate(inflater)
        job = Job()
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // TODO check if app, activity, b can be null
        if (app.profile == null || !isAdded)
            return

        b.closeButton.setImageDrawable(
                IconicsDrawable(activity, CommunityMaterial.Icon2.cmd_window_close)
                        .colorAttr(activity, android.R.attr.textColorSecondary)
                        .sizeDp(12)
        )
        b.closeButton.setOnClickListener { activity.navigateUp() }

        val messageId = arguments?.getLong("messageId")
        if (messageId == null) {
            activity.navigateUp()
            return
        }

        launch {
            val deferred = async(Dispatchers.Default) {
                val msg = app.db.messageDao().getById(App.profileId, messageId)?.also {
                    it.recipients = app.db.messageRecipientDao().getAllByMessageId(it.profileId, it.id)
                    if (it.body != null && !it.seen) {
                        app.db.metadataDao().setSeen(it.profileId, message, true)
                    }
                }
                msg
            }
            val msg = deferred.await() ?: run {
                return@launch
            }
            message = msg
            b.subject.text = message.subject
            checkMessage()
        }

        // click to expand subject and sender
        b.subject.onClick {
            it.maxLines = if (it.maxLines == 30) 2 else 30
        }
        b.sender.onClick {
            it.maxLines = if (it.maxLines == 30) 2 else 30
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN, sticky = true)
    fun onMessageGetEvent(event: MessageGetEvent) {
        // TODO remove this: message = event.message
        showMessage()
    }

    private fun checkMessage() {
        if (message.body == null) {
            EdziennikTask.messageGet(App.profileId, message).enqueue(activity)
            return
        }

        var readByAll = true
        message.recipients?.forEach { recipient ->
            if (recipient.id == -1L)
                recipient.fullName = app.profile.accountNameLong ?: app.profile.studentNameLong
            if (message.type == TYPE_SENT && recipient.readDate < 1)
                readByAll = false
        }
        // if a sent msg is not read by everyone, download it again to check the read status
        if (!readByAll) {
            EdziennikTask.messageGet(App.profileId, message).enqueue(activity)
            return
        }

        showMessage()
    }

    private fun showMessage() {
        b.body.text = Html.fromHtml(message.body?.replace("\\[META:[A-z0-9]+;[0-9-]+]".toRegex(), ""))
        b.date.text = getString(R.string.messages_date_time_format, Date.fromMillis(message.addedDate).formattedStringShort, Time.fromMillis(message.addedDate).stringHM)

        val messageInfo = MessagesUtils.getMessageInfo(app, message, 40, 20, 14, 10)
        b.profileBackground.setImageBitmap(messageInfo.profileImage)
        b.sender.text = messageInfo.profileName

        b.subject.text = message.subject

        val messageRecipients = StringBuilder("<ul>")
        message.recipients?.forEach { recipient ->
            when (recipient.readDate) {
                -1L -> messageRecipients.append(getString(
                        R.string.messages_recipients_list_unknown_state_format,
                        recipient.fullName
                ))
                0L -> messageRecipients.append(getString(
                        R.string.messages_recipients_list_unread_format,
                        recipient.fullName
                ))
                1L -> messageRecipients.append(getString(
                        R.string.messages_recipients_list_read_unknown_date_format,
                        recipient.fullName
                ))
                else -> messageRecipients.append(getString(
                        R.string.messages_recipients_list_read_format,
                        recipient.fullName,
                        Date.fromMillis(recipient.readDate).formattedString,
                        Time.fromMillis(recipient.readDate).stringHM
                ))
            }
        }
        messageRecipients.append("</ul>")
        b.recipients.text = Html.fromHtml(messageRecipients.toString())

        showAttachments()

        b.progress.visibility = View.GONE
        Anim.fadeIn(b.content, 200, null)
        MessagesFragment.pageSelection = min(message.type, 1)
    }

    private fun showAttachments() {
        if (message.attachmentIds != null) {
            val insertPoint = b.attachments

            val chipLayoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            chipLayoutParams.setMargins(0, Utils.dpToPx(8), 0, Utils.dpToPx(8))

            val progressLayoutParams = FrameLayout.LayoutParams(Utils.dpToPx(18), Utils.dpToPx(18))
            progressLayoutParams.setMargins(Utils.dpToPx(8), 0, Utils.dpToPx(8), 0)
            progressLayoutParams.gravity = END or CENTER_VERTICAL

            // CREATE VIEWS AND AN OBJECT FOR EVERY ATTACHMENT

            message.attachmentNames.forEachIndexed { index, name ->
                val messageId = message.id
                val id = message.attachmentIds[index]
                val size = message.attachmentSizes[index]
                // create the parent
                val attachmentLayout = FrameLayout(b.root.context)
                attachmentLayout.setPadding(Utils.dpToPx(16), 0, Utils.dpToPx(16), 0)

                val attachmentChip = Chip(attachmentLayout.context)
                //attachmentChip.setChipBackgroundColorResource(ThemeUtils.getChipColorRes());
                attachmentChip.layoutParams = chipLayoutParams
                attachmentChip.height = Utils.dpToPx(40)

                // show the file size or not
                if (size == -1L)
                    attachmentChip.text = getString(R.string.messages_attachment_no_size_format, name)
                else
                    attachmentChip.text = getString(R.string.messages_attachment_format, name, readableFileSize(size))
                attachmentChip.ellipsize = TextUtils.TruncateAt.MIDDLE

                // create an icon for the attachment
                var icon: IIcon = CommunityMaterial.Icon.cmd_file
                when (Utils.getExtensionFromFileName(name)) {
                    "txt" -> icon = CommunityMaterial.Icon.cmd_file_document
                    "doc", "docx", "odt", "rtf" -> icon = CommunityMaterial.Icon.cmd_file_word
                    "xls", "xlsx", "ods" -> icon = CommunityMaterial.Icon.cmd_file_excel
                    "ppt", "pptx", "odp" -> icon = CommunityMaterial.Icon.cmd_file_powerpoint
                    "pdf" -> icon = CommunityMaterial.Icon.cmd_file_pdf
                    "mp3", "wav", "aac" -> icon = CommunityMaterial.Icon.cmd_file_music
                    "mp4", "avi", "3gp", "mkv", "flv" -> icon = CommunityMaterial.Icon.cmd_file_video
                    "jpg", "jpeg", "png", "bmp", "gif" -> icon = CommunityMaterial.Icon.cmd_file_image
                    "zip", "rar", "tar", "7z" -> icon = CommunityMaterial.Icon.cmd_file_lock
                }
                attachmentChip.chipIcon = IconicsDrawable(activity).color(IconicsColor.colorRes(R.color.colorPrimary)).icon(icon).size(IconicsSize.dp(26))
                attachmentChip.closeIcon = IconicsDrawable(activity).icon(CommunityMaterial.Icon.cmd_check).size(IconicsSize.dp(18)).color(IconicsColor.colorInt(Utils.getAttr(activity, android.R.attr.textColorPrimary)))
                attachmentChip.isCloseIconVisible = false
                // set the object's index in the attachmentList as the tag
                attachmentChip.tag = index
                attachmentChip.setOnClickListener { v ->
                    if (v.tag is Int) {
                        // TODO downloadAttachment(v.tag as Int)
                    }
                }
                attachmentLayout.addView(attachmentChip)

                val attachmentProgress = ProgressBar(attachmentLayout.context)
                attachmentProgress.layoutParams = progressLayoutParams
                attachmentProgress.visibility = View.GONE
                attachmentLayout.addView(attachmentProgress)

                insertPoint.addView(attachmentLayout)
                // create an object and add to the list
                val a = Attachment(App.profileId, messageId, id, name, size, attachmentLayout, attachmentChip, attachmentProgress)
                attachmentList.add(a)
                // check if the file is already downloaded. Show the check icon if necessary and set `downloaded` to true.
                checkAttachment(a)

            }
        } else {
            // no attachments found
            b.attachmentsTitle.visibility = View.GONE
        }
    }

    private fun checkAttachment(attachment: Attachment) {
        val storageDir = Environment.getExternalStoragePublicDirectory("Szkolny.eu")
        storageDir.mkdirs()

        val attachmentDataFile = File(storageDir, "." + attachment.profileId + "_" + attachment.messageId + "_" + attachment.attachmentId)
        if (attachmentDataFile.exists()) {
            try {
                val attachmentFileName = getStringFromFile(attachmentDataFile)
                val attachmentFile = File(attachmentFileName)
                if (attachmentFile.exists()) {
                    attachment.downloaded = attachmentFileName
                    attachment.chip.isCloseIconVisible = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                //app.apiEdziennik.guiReportException(activity, 355, e)
            }

        }
    }

    override fun onStart() {
        EventBus.getDefault().register(this)
        super.onStart()
    }
    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }
    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private class Attachment(
            var profileId: Int,
            var messageId: Long,
            var attachmentId: Long,
            var attachmentName: String,
            var attachmentSize: Long,
            var parent: FrameLayout,
            var chip: Chip,
            var progressBar: ProgressBar
    ) {
        /**
         * An absolute path of the downloaded file. `null` if not downloaded yet.
         */
        internal var downloaded: String? = null
    }
}
