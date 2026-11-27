package com.aitts.v2.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aitts.v2.di.AppContainer
import com.aitts.v2.model.PresetCatalog
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.RuleCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class RulesUiState(
    val selectedCategory: RuleCategory? = null,
    val previewInput: String = "打开 https://demo.test 查看 2026年 14:30 报告……（本章完）",
    val message: String? = null
)

class RulesViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(RulesUiState())
    val uiState: StateFlow<RulesUiState> = _uiState.asStateFlow()

    val rulesFlow = container.preferences.rulesFlow
    val settingsFlow = container.preferences.settingsFlow

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
    fun selectCategory(cat: RuleCategory?) = _uiState.update { it.copy(selectedCategory = cat) }
    fun updatePreviewInput(text: String) = _uiState.update { it.copy(previewInput = text) }

    fun toggleRule(rule: ReplacementRule, enabled: Boolean) =
        container.preferences.upsertRule(rule.copy(enabled = enabled))

    fun toggleNumberNormalization(enabled: Boolean) {
        val cur = settingsFlow.value
        container.preferences.updateSettings(cur.copy(isNumberNormalizationEnabled = enabled))
    }

    fun saveRule(rule: ReplacementRule) {
        val fixed = if (rule.id.isBlank()) rule.copy(id = "rule_${UUID.randomUUID().toString().take(6)}") else rule
        container.preferences.upsertRule(fixed)
    }

    fun deleteRule(id: String) = container.preferences.deleteRule(id)

    fun batchToggle(enabled: Boolean) {
        container.preferences.saveRules(rulesFlow.value.map { it.copy(enabled = enabled) })
    }

    fun importNovelSymbolsPack() {
        val existingIds = rulesFlow.value.map { it.id }.toSet()
        val toAdd = PresetCatalog.novelSymbolsPack.filterNot { it.id in existingIds }
        container.preferences.saveRules(rulesFlow.value + toAdd)
        _uiState.update { it.copy(message = "已导入符号净化包，新增 ${toAdd.size} 条规则") }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RulesViewModel(container) as T
    }
}
