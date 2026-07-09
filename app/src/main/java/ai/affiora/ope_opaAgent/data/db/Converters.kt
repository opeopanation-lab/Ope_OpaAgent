package ai.affiora.ope_opaAgent.data.db

import ai.affiora.ope_opaAgent.data.model.MessageRole
import ai.affiora.ope_opaAgent.data.model.ToolStatus
import androidx.room.TypeConverter

class Converters {

    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toMessageRole(value: String): MessageRole = MessageRole.valueOf(value)

    @TypeConverter
    fun fromToolStatus(status: ToolStatus): String = status.name

    @TypeConverter
    fun toToolStatus(value: String): ToolStatus = ToolStatus.valueOf(value)
}
