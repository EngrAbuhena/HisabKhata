package com.hisabkhata.data.local

import androidx.room.TypeConverter
import com.hisabkhata.data.local.entity.PersonType

class Converters {
    @TypeConverter fun personTypeToString(type: PersonType): String = type.name
    @TypeConverter fun stringToPersonType(value: String): PersonType = PersonType.valueOf(value)
}
