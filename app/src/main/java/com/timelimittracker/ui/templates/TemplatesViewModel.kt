package com.timelimittracker.ui.templates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timelimittracker.TimeLimitApp
import com.timelimittracker.data.db.entities.GlobalTemplatesEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TemplatesViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as TimeLimitApp).settingsRepo
    private val _drafts = MutableStateFlow(GlobalTemplatesEntity())
    private val _saved = MutableStateFlow(GlobalTemplatesEntity())

    val drafts: StateFlow<GlobalTemplatesEntity> = _drafts.asStateFlow()
    val isDirty: StateFlow<Boolean> = combine(_drafts, _saved) { d, s -> d != s }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            val existing = repo.getTemplates()
            _drafts.value = existing
            _saved.value = existing
        }
    }

    fun updateMain(text: String) { _drafts.value = _drafts.value.copy(mainMessage = text) }
    fun updateReminder1(text: String) { _drafts.value = _drafts.value.copy(reminder1 = text.ifBlank { null }) }
    fun updateReminder2(text: String) { _drafts.value = _drafts.value.copy(reminder2 = text.ifBlank { null }) }
    fun updateReminder3(text: String) { _drafts.value = _drafts.value.copy(reminder3 = text.ifBlank { null }) }

    fun save() {
        viewModelScope.launch {
            repo.upsertTemplates(_drafts.value)
            _saved.value = _drafts.value
        }
    }
}
