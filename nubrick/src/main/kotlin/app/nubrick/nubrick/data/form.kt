package app.nubrick.nubrick.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal sealed class FormValue {
    class Bool(val bool: Boolean) : FormValue()
    class StrList(val list: List<String>) : FormValue()
    class Str(val str: String) : FormValue()

    fun toJsonElement(): JsonElement {
        return when (this) {
            is Bool -> JsonPrimitive(this.bool)
            is Str -> JsonPrimitive(this.str)
            is StrList -> JsonArray(this.list.map { JsonPrimitive(it) })
        }
    }
}

internal interface FormRepository {
    val formValues: StateFlow<Map<String, FormValue>>
    fun getFormData(): Map<String, JsonElement>
    fun setValue(key: String, value: FormValue)
    fun getValue(key: String): FormValue?
}

internal class FormRepositoryImpl : FormRepository {
    private val _formValues = MutableStateFlow<Map<String, FormValue>>(emptyMap())
    override val formValues: StateFlow<Map<String, FormValue>> = _formValues.asStateFlow()

    override fun getFormData(): Map<String, JsonElement> {
        return this.formValues.value.toFormData()
    }

    override fun getValue(key: String): FormValue? {
        return this._formValues.value[key]
    }

    override fun setValue(key: String, value: FormValue) {
        this._formValues.update { it + (key to value) }
    }
}

internal fun Map<String, FormValue>.toFormData(): Map<String, JsonElement> {
    return this.entries.associate { it.key to it.value.toJsonElement() }
}
