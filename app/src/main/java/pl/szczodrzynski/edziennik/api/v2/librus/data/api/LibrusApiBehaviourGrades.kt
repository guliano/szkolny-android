/*
 * Copyright (c) Kacper Ziubryniewicz 2019-12-3
 */

package pl.szczodrzynski.edziennik.api.v2.librus.data.api

import pl.szczodrzynski.edziennik.*
import pl.szczodrzynski.edziennik.api.v2.librus.DataLibrus
import pl.szczodrzynski.edziennik.api.v2.librus.ENDPOINT_LIBRUS_API_BEHAVIOUR_GRADES
import pl.szczodrzynski.edziennik.api.v2.librus.data.LibrusApi
import pl.szczodrzynski.edziennik.data.db.modules.api.SYNC_ALWAYS
import pl.szczodrzynski.edziennik.data.db.modules.grades.Grade
import pl.szczodrzynski.edziennik.data.db.modules.grades.GradeCategory
import pl.szczodrzynski.edziennik.data.db.modules.metadata.Metadata
import pl.szczodrzynski.edziennik.utils.models.Date
import java.text.DecimalFormat

class LibrusApiBehaviourGrades(override val data: DataLibrus,
                               val onSuccess: () -> Unit) : LibrusApi(data) {
    companion object {
        const val TAG = "LibrusApiBehaviourGrades"
    }

    private val nameFormat by lazy { DecimalFormat("#.##") }

    init { data.profile?.let { profile ->
        apiGet(TAG, "BehaviourGrades/Points") { json ->

            val semester1StartGradeObject = Grade(
                    profileId,
                    -1,
                    data.app.getString(R.string.grade_start_points),
                    0xffbdbdbd.toInt(),
                    data.app.getString(R.string.grade_start_points_format, 1),
                    nameFormat.format(data.startPointsSemester1),
                    data.startPointsSemester1.toFloat(),
                    -1f,
                    1,
                    -1,
                    1
            ).apply { type = Grade.TYPE_BEHAVIOUR }

            data.gradeList.add(semester1StartGradeObject)
            data.metadataList.add(Metadata(
                    profileId,
                    Metadata.TYPE_GRADE,
                    -1,
                    true,
                    true,
                    profile.getSemesterStart(1).inMillis
            ))

            val semester2StartGradeObject = Grade(
                    profileId,
                    -2,
                    data.app.getString(R.string.grade_start_points),
                    0xffbdbdbd.toInt(),
                    data.app.getString(R.string.grade_start_points_format, 2),
                    nameFormat.format(data.startPointsSemester2),
                    data.startPointsSemester2.toFloat(),
                    -1f,
                    2,
                    -1,
                    1
            ).apply { type = Grade.TYPE_BEHAVIOUR }

            data.gradeList.add(semester2StartGradeObject)
            data.metadataList.add(Metadata(
                    profileId,
                    Metadata.TYPE_GRADE,
                    -2,
                    true,
                    true,
                    profile.getSemesterStart(2).inMillis
            ))

            json.getJsonArray("Grades")?.asJsonObjectList()?.forEach { grade ->
                val id = grade.getLong("Id") ?: return@forEach
                val value = grade.getFloat("Value")
                val shortName = grade.getString("ShortName")
                val semester = grade.getInt("Semester") ?: profile.currentSemester
                val teacherId = grade.getJsonObject("AddedBy")?.getLong("Id") ?: -1
                val addedDate = grade.getString("AddDate")?.let { Date.fromIso(it) }
                        ?: System.currentTimeMillis()

                val name = when {
                    value != null -> (if (value >= 0) "+" else "") + nameFormat.format(value)
                    shortName != null -> shortName
                    else -> return@forEach
                }

                val color = data.getColor(when {
                    value == null || value == 0f -> 12
                    value > 0 -> 16
                    value < 0 -> 26
                    else -> 12
                })

                val categoryId = grade.getJsonObject("Category")?.getLong("Id") ?: -1
                val category = data.gradeCategories.singleOrNull {
                    it.categoryId == categoryId && it.type == GradeCategory.TYPE_BEHAVIOUR
                }

                val gradeObject = Grade(
                        profileId,
                        id,
                        category?.text ?: "",
                        color,
                        "",
                        name,
                        value ?: category?.valueFrom ?: 0f,
                        -1f,
                        semester,
                        teacherId,
                        1
                ).apply {
                    type = Grade.TYPE_BEHAVIOUR
                    valueMax = category?.valueTo ?: 0f
                }

                data.gradeList.add(gradeObject)
                data.metadataList.add(Metadata(
                        profileId,
                        Metadata.TYPE_GRADE,
                        id,
                        profile.empty,
                        profile.empty,
                        addedDate
                ))
            }

            data.setSyncNext(ENDPOINT_LIBRUS_API_BEHAVIOUR_GRADES, SYNC_ALWAYS)
            onSuccess()
        }
    }}
}