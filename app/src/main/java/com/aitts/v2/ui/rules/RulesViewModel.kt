package com.aitts.v2.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aitts.v2.di.AppContainer
import com.aitts.v2.engine.text.TextPipeline
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
    val previewInput: String = "他来到重庆银行，遇到丹田筑基的老者，打开 https://demo.test 查看 2026年 AI 报告。",
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

    fun previewProcessed(input: String): String {
        val settings = settingsFlow.value
        return TextPipeline.preprocessFlat(
            rawText = input,
            rules = rulesFlow.value,
            enableNumbers = settings.isNumberNormalizationEnabled,
            enableAcronyms = settings.isAcronymNormalizationEnabled
        )
    }

    fun toggleRule(rule: ReplacementRule, enabled: Boolean) =
        container.preferences.upsertRule(rule.copy(enabled = enabled))

    fun saveRule(rule: ReplacementRule) {
        val fixed = if (rule.id.isBlank()) rule.copy(id = "rule_${UUID.randomUUID().toString().take(6)}") else rule
        container.preferences.upsertRule(fixed)
    }

    fun deleteRule(id: String) = container.preferences.deleteRule(id)

    fun batchToggle(enabled: Boolean) {
        container.preferences.saveRules(rulesFlow.value.map { it.copy(enabled = enabled) })
    }

    fun importPresetPack(pack: List<ReplacementRule>, label: String) {
        val existingIds = rulesFlow.value.map { it.id }.toSet()
        val toAdd = pack.filterNot { it.id in existingIds }
        container.preferences.saveRules(rulesFlow.value + toAdd)
        _uiState.update { it.copy(message = "已导入「$label」新增 ${toAdd.size} 条规则") }
    }

    fun importXianxiaPack() = importPresetPack(PresetCatalog.xianxiaRulesPack, "修仙多音字包")
    fun importNovelSymbolsPack() = importPresetPack(PresetCatalog.novelSymbolsPack, "网文符号净化包")

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RulesViewModel(container) as T
    }
}
