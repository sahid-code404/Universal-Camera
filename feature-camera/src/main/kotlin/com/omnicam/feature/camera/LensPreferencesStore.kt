package com.omnicam.feature.camera

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnicam.camera.capability.ValuableCameraRoute
import com.omnicam.core.model.LensFacing
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.lensPreferencesDataStore by preferencesDataStore(name = "omnicam_lens_layout")

data class LensPreferences(
    val rearOrder: List<String> = emptyList(),
    val frontOrder: List<String> = emptyList(),
    val disabledCameraIds: Set<String> = emptySet(),
) {
    fun isEnabled(cameraId: String): Boolean = cameraId !in disabledCameraIds

    fun applyOrder(
        routes: List<ValuableCameraRoute>,
        facing: LensFacing,
    ): List<ValuableCameraRoute> {
        val storedOrder = when (facing) {
            LensFacing.BACK -> rearOrder
            LensFacing.FRONT -> frontOrder
            else -> emptyList()
        }
        val routeById = routes.associateBy { it.camera.id }
        val ordered = storedOrder.mapNotNull(routeById::get)
        val alreadyAdded = ordered.mapTo(mutableSetOf()) { it.camera.id }
        return ordered + routes.filterNot { it.camera.id in alreadyAdded }
    }
}

interface LensPreferencesStore {
    val preferences: Flow<LensPreferences>

    suspend fun setEnabled(cameraId: String, enabled: Boolean)

    suspend fun setOrder(facing: LensFacing, cameraIds: List<String>)

    suspend fun reset()
}

class DataStoreLensPreferencesStore(
    context: Context,
) : LensPreferencesStore {
    private val dataStore = context.applicationContext.lensPreferencesDataStore

    override val preferences: Flow<LensPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toLensPreferences)

    override suspend fun setEnabled(cameraId: String, enabled: Boolean) {
        dataStore.edit { mutable ->
            val disabled = mutable[Keys.disabledIds].orEmpty().toMutableSet()
            if (enabled) disabled.remove(cameraId) else disabled.add(cameraId)
            mutable[Keys.disabledIds] = disabled
        }
    }

    override suspend fun setOrder(facing: LensFacing, cameraIds: List<String>) {
        val key = when (facing) {
            LensFacing.BACK -> Keys.rearOrder
            LensFacing.FRONT -> Keys.frontOrder
            else -> return
        }
        dataStore.edit { mutable -> mutable[key] = encodeOrder(cameraIds.distinct()) }
    }

    override suspend fun reset() {
        dataStore.edit { mutable ->
            mutable.remove(Keys.rearOrder)
            mutable.remove(Keys.frontOrder)
            mutable.remove(Keys.disabledIds)
        }
    }

    private fun toLensPreferences(preferences: Preferences) = LensPreferences(
        rearOrder = decodeOrder(preferences[Keys.rearOrder]),
        frontOrder = decodeOrder(preferences[Keys.frontOrder]),
        disabledCameraIds = preferences[Keys.disabledIds].orEmpty(),
    )

    private object Keys {
        val rearOrder = stringPreferencesKey("rear_order")
        val frontOrder = stringPreferencesKey("front_order")
        val disabledIds = stringSetPreferencesKey("disabled_camera_ids")
    }
}

private fun encodeOrder(cameraIds: List<String>): String = cameraIds.joinToString("|") { id ->
    Base64.encodeToString(id.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
}

private fun decodeOrder(value: String?): List<String> = value
    ?.takeIf { it.isNotBlank() }
    ?.split('|')
    ?.mapNotNull { encoded ->
        runCatching {
            String(Base64.decode(encoded, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
        }.getOrNull()
    }
    .orEmpty()
