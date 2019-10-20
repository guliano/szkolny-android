/*
 * Copyright (c) Kuba Szczodrzyński 2019-9-28.
 */

package pl.szczodrzynski.edziennik.api.v2

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import pl.szczodrzynski.edziennik.App
import pl.szczodrzynski.edziennik.R
import pl.szczodrzynski.edziennik.api.v2.events.SyncErrorEvent
import pl.szczodrzynski.edziennik.api.v2.events.SyncFinishedEvent
import pl.szczodrzynski.edziennik.api.v2.events.SyncProfileFinishedEvent
import pl.szczodrzynski.edziennik.api.v2.events.SyncProgressEvent
import pl.szczodrzynski.edziennik.api.v2.events.requests.*
import pl.szczodrzynski.edziennik.api.v2.events.task.ErrorReportTask
import pl.szczodrzynski.edziennik.api.v2.events.task.NotifyTask
import pl.szczodrzynski.edziennik.api.v2.interfaces.EdziennikCallback
import pl.szczodrzynski.edziennik.api.v2.interfaces.EdziennikInterface
import pl.szczodrzynski.edziennik.api.v2.librus.Librus
import pl.szczodrzynski.edziennik.api.v2.mobidziennik.Mobidziennik
import pl.szczodrzynski.edziennik.api.v2.models.ApiError
import pl.szczodrzynski.edziennik.api.v2.models.ApiTask
import pl.szczodrzynski.edziennik.api.v2.template.Template
import pl.szczodrzynski.edziennik.api.v2.vulcan.Vulcan
import pl.szczodrzynski.edziennik.data.db.modules.login.LoginStore
import pl.szczodrzynski.edziennik.data.db.modules.profiles.Profile
import kotlin.math.max
import kotlin.math.min

class ApiService : Service() {
    companion object {
        const val TAG = "ApiService"
        const val NOTIFICATION_API_CHANNEL_ID = "pl.szczodrzynski.edziennik.GET_DATA"
    }

    private val app by lazy { applicationContext as App }

    private val taskQueue = mutableListOf<ApiTask>()
    private val errorList = mutableListOf<ApiError>()
    private var queueHasErrorReportTask = false
    private var queueHasNotifyTask = false

    private var serviceClosed = false
    private var taskCancelled = false
    private var taskRunningObject: ApiTask? = null // for debug purposes
    private var taskRunning = false
    private var taskRunningId = -1
    private var taskMaximumId = 0
    private var edziennikInterface: EdziennikInterface? = null

    private var taskProfileId = -1
    private var taskProfileName: String? = null
    private var taskProgress = 0
    private var taskProgressRes: Int? = null

    private val notification by lazy { EdziennikNotification(this) }

    /*    ______    _     _                  _ _       _____      _ _ _                _
         |  ____|  | |   (_)                (_) |     / ____|    | | | |              | |
         | |__   __| |_____  ___ _ __  _ __  _| | __ | |     __ _| | | |__   __ _  ___| | __
         |  __| / _` |_  / |/ _ \ '_ \| '_ \| | |/ / | |    / _` | | | '_ \ / _` |/ __| |/ /
         | |___| (_| |/ /| |  __/ | | | | | | |   <  | |___| (_| | | | |_) | (_| | (__|   <
         |______\__,_/___|_|\___|_| |_|_| |_|_|_|\_\  \_____\__,_|_|_|_.__/ \__,_|\___|_|\*/
    private val taskCallback = object : EdziennikCallback {
        override fun onCompleted() {
            edziennikInterface = null
            if (taskRunningObject is SyncProfileRequest) {
                // post an event if this task is a sync, not e.g. first login or message getting
                if (!taskCancelled) {
                    EventBus.getDefault().post(SyncProfileFinishedEvent(taskProfileId))
                }
                // add a notifying task to create data notifications of this profile
                addNotifyTask()
            }
            notification.setIdle().post()
            taskRunningObject = null
            taskRunning = false
            taskRunningId = -1
            sync()
        }

        override fun onError(apiError: ApiError) {
            if (!queueHasErrorReportTask) {
                queueHasErrorReportTask = true
                taskQueue += ErrorReportTask().apply {
                    taskId = ++taskMaximumId
                }
            }
            apiError.profileId = taskProfileId
            EventBus.getDefault().post(SyncErrorEvent(apiError))
            errorList.add(apiError)
            apiError.throwable?.printStackTrace()
            if (apiError.isCritical) {
                // if this error ends the sync, post an error notification
                // if this is a sync task, create a notifying task
                if (taskRunningObject is SyncProfileRequest) {
                    // add a notifying task to create data notifications of this profile
                    addNotifyTask()
                }
                notification.setCriticalError().post()
                taskRunningObject = null
                taskRunning = false
                taskRunningId = -1
                sync()
            }
            else {
                notification.addError().post()
            }
        }

        override fun onProgress(step: Int) {
            taskProgress += step
            taskProgress = min(100, taskProgress)
            EventBus.getDefault().post(SyncProgressEvent(taskProfileId, taskProfileName, taskProgress, taskProgressRes))
            notification.setProgress(taskProgress).post()
        }

        override fun onStartProgress(stringRes: Int) {
            taskProgressRes = stringRes
            EventBus.getDefault().post(SyncProgressEvent(taskProfileId, taskProfileName, taskProgress, taskProgressRes))
            notification.setProgressRes(taskProgressRes!!).post()
        }

        fun addNotifyTask() {
            if (!queueHasNotifyTask) {
                queueHasNotifyTask = true
                taskQueue.add(
                        if (queueHasErrorReportTask) max(taskQueue.size-1, 0) else taskQueue.size,
                        NotifyTask().apply {
                            taskId = ++taskMaximumId
                        }
                )
            }
        }
    }

    /*    _______        _                               _   _
         |__   __|      | |                             | | (_)
            | | __ _ ___| | __   _____  _____  ___ _   _| |_ _  ___  _ __
            | |/ _` / __| |/ /  / _ \ \/ / _ \/ __| | | | __| |/ _ \| '_ \
            | | (_| \__ \   <  |  __/>  <  __/ (__| |_| | |_| | (_) | | | |
            |_|\__,_|___/_|\_\  \___/_/\_\___|\___|\__,_|\__|_|\___/|_| |*/
    private fun sync() {
        if (taskRunning)
            return
        if (taskQueue.size <= 0 || serviceClosed) {
            serviceClosed = false
            allCompleted()
            return
        }

        val task = taskQueue.removeAt(0)
        taskCancelled = false
        taskRunning = true
        taskRunningId = task.taskId
        taskRunningObject = task

        if (task is ErrorReportTask) {
            queueHasErrorReportTask = false
            task.run(notification, errorList)
            taskRunningObject = null
            taskRunning = false
            taskRunningId = -1
            sync()
            return
        }

        if (task is NotifyTask) {
            queueHasNotifyTask = false
            task.run(app)
            taskRunningObject = null
            taskRunning = false
            taskRunningId = -1
            sync()
            return
        }

        val profile: Profile?
        val loginStore: LoginStore
        if (task is FirstLoginRequest) {
            // get the requested profile and login store
            profile = null
            loginStore = task.loginStore
            // save the profile ID and name as the current task's
            taskProfileId = -1
            taskProfileName = getString(R.string.edziennik_notification_api_first_login_title)
        }
        else {
            // get the requested profile and login store
            profile = app.db.profileDao().getByIdNow(task.profileId)
            if (profile == null || !profile.syncEnabled) {
                return
            }
            loginStore = app.db.loginStoreDao().getByIdNow(profile.loginStoreId)
            if (loginStore == null) {
                return
            }
            // save the profile ID and name as the current task's
            taskProfileId = profile.id
            taskProfileName = profile.name
        }
        taskProgress = 0
        taskProgressRes = null

        // update the notification
        notification.setCurrentTask(taskRunningId, taskProfileName).post()

        edziennikInterface = when (loginStore.type) {
            LOGIN_TYPE_LIBRUS -> Librus(app, profile, loginStore, taskCallback)
            LOGIN_TYPE_MOBIDZIENNIK -> Mobidziennik(app, profile, loginStore, taskCallback)
            LOGIN_TYPE_VULCAN -> Vulcan(app, profile, loginStore, taskCallback)
            LOGIN_TYPE_TEMPLATE -> Template(app, profile, loginStore, taskCallback)
            else -> null
        }
        if (edziennikInterface == null) {
            return
        }

        when (task) {
            is SyncProfileRequest -> edziennikInterface?.sync(
                    featureIds = task.viewIds?.flatMap { Features.getIdsByView(it.first, it.second) } ?: Features.getAllIds(),
                    viewId = task.viewIds?.get(0)?.first)
            is MessageGetRequest -> edziennikInterface?.getMessage(task.messageId)
            is FirstLoginRequest -> edziennikInterface?.firstLogin()
        }
    }

    private fun allCompleted() {
        EventBus.getDefault().post(SyncFinishedEvent())
        stopSelf()
    }

    /*    ______               _   ____
         |  ____|             | | |  _ \
         | |____   _____ _ __ | |_| |_) |_   _ ___
         |  __\ \ / / _ \ '_ \| __|  _ <| | | / __|
         | |___\ V /  __/ | | | |_| |_) | |_| \__ \
         |______\_/ \___|_| |_|\__|____/ \__,_|__*/
    @Subscribe(sticky = true, threadMode = ThreadMode.ASYNC)
    fun onSyncRequest(request: SyncRequest) {
        EventBus.getDefault().removeStickyEvent(request)
        Log.d(TAG, request.toString())

        app.db.profileDao().idsForSyncNow.forEach { id ->
            taskQueue += SyncProfileRequest(id, null).apply {
                taskId = ++taskMaximumId
            }
        }
        sync()
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.ASYNC)
    fun onSyncProfileRequest(request: SyncProfileRequest) {
        EventBus.getDefault().removeStickyEvent(request)
        Log.d(TAG, request.toString())

        taskQueue += request.apply {
            taskId = ++taskMaximumId
        }
        sync()
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.ASYNC)
    fun onMessageGetRequest(request: MessageGetRequest) {
        EventBus.getDefault().removeStickyEvent(request)
        Log.d(TAG, request.toString())

        taskQueue += request.apply {
            taskId = ++taskMaximumId
        }
        sync()
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.ASYNC)
    fun onFirstLoginRequest(request: FirstLoginRequest) {
        EventBus.getDefault().removeStickyEvent(request)
        Log.d(TAG, request.toString())

        taskQueue += request.apply {
            taskId = ++taskMaximumId
        }
        sync()
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.ASYNC)
    fun onTaskCancelRequest(request: TaskCancelRequest) {
        EventBus.getDefault().removeStickyEvent(request)
        Log.d(TAG, request.toString())

        taskCancelled = true
        edziennikInterface?.cancel()
    }
    @Subscribe(sticky = true, threadMode = ThreadMode.ASYNC)
    fun onServiceCloseRequest(request: ServiceCloseRequest) {
        EventBus.getDefault().removeStickyEvent(request)
        Log.d(TAG, request.toString())

        serviceClosed = true
        taskCancelled = true
        edziennikInterface?.cancel()
        stopSelf()
    }

    /*     _____                 _                                     _     _
          / ____|               (_)                                   (_)   | |
         | (___   ___ _ ____   ___  ___ ___    _____   _____ _ __ _ __ _  __| | ___  ___
          \___ \ / _ \ '__\ \ / / |/ __/ _ \  / _ \ \ / / _ \ '__| '__| |/ _` |/ _ \/ __|
          ____) |  __/ |   \ V /| | (_|  __/ | (_) \ V /  __/ |  | |  | | (_| |  __/\__ \
         |_____/ \___|_|    \_/ |_|\___\___|  \___/ \_/ \___|_|  |_|  |_|\__,_|\___||__*/
    override fun onCreate() {
        EventBus.getDefault().register(this)
        notification.setIdle().setCloseAction()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(EdziennikNotification.NOTIFICATION_ID, notification.notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}