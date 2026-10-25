package org.dekufoundation.satr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PersonModelSelectionCard(
    selected: PersonModelProfile,
    active: PersonModelProfile,
    onSelect: (PersonModelProfile) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("محرك النموذج", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PersonModelProfile.values().forEach { option ->
                val label = if (option == PersonModelProfile.FAST_SEMANTIC) "سريع وخفيف" else "أقنعة منفصلة لكل شخص"
                val description = if (option == PersonModelProfile.FAST_SEMANTIC)
                    "LR-ASPP · نحو 3.52 MB · قناع موحّد للأشخاص"
                else
                    "RTMDet-Ins-S · 43.24 MB أوزان · قناع مستقل لكل شخص"
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selected == option) {
                            Text(
                                "مختار",
                                modifier = Modifier.padding(start = 8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            OutlinedButton(onClick = { onSelect(option) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("اختيار")
                            }
                        }
                    }
                    if (option == PersonModelProfile.RTMDET_INS_S) {
                        Text(
                            "يزيد حجم الحزمة واستهلاك الذاكرة. أرقام CPU على خادم البناء ليست قياساً لأداء الهاتف.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }
            Text(
                "المحرك النشط في الجلسة الحالية: " +
                    if (active == PersonModelProfile.RTMDET_INS_S) "RTMDet-Ins-S" else "LR-ASPP الخفيف",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PersonTargetingCard(
    snapshot: SatrSessionSnapshot,
    selectedProfile: PersonModelProfile,
    mode: PersonTargetMode,
    selectedTrackIds: Set<Long>,
    onModeChange: (PersonTargetMode) -> Unit,
    onTogglePerson: (Long, Boolean) -> Unit,
) {
    val instanceModelActive = snapshot.personModelProfile == PersonModelProfile.RTMDET_INS_S &&
        snapshot.phase != SatrPhase.STOPPED && snapshot.phase != SatrPhase.ERROR
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("اختيار الأشخاص", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "اختر يدوياً الأشخاص الذين تريد طمسهم أو إبقاءهم ظاهرين. النموذج لا يصنّف الجنس، ولا يستخدم الشعر أو الملابس كتخمين.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 21.sp,
            )
            if (selectedProfile != snapshot.personModelProfile) {
                Text(
                    "النموذج المختار سيتفعّل عند بدء جلسة حماية جديدة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!instanceModelActive) {
                Text(
                    "ابدأ جلسة جديدة باستخدام RTMDet-Ins-S لتحديد الأشخاص كلٌّ على حدة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("سياسة الطمس", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                PersonTargetMode.values().forEach { option ->
                    val label = when (option) {
                        PersonTargetMode.MASK_ALL -> "طمس الجميع"
                        PersonTargetMode.MASK_SELECTED_ONLY -> "طمس المحددين فقط"
                        PersonTargetMode.MASK_ALL_EXCEPT_SELECTED -> "طمس الجميع ما عدا المحددين"
                    }
                    val enabled = option != PersonTargetMode.MASK_SELECTED_ONLY || selectedTrackIds.isNotEmpty()
                    if (option == mode) {
                        Button(
                            onClick = { onModeChange(option) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { onModeChange(option) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text(label) }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "الأشخاص الظاهرون في آخر إطار: " + snapshot.detectedPeople.size,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (snapshot.detectedPeople.isEmpty()) {
                    Text(
                        "ابدأ العرض الذي تريد حمايته، ثم افتح الإعدادات لتحديد الأشخاص الظاهرين.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    snapshot.detectedPeople.forEachIndexed { index, person ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = person.trackId in selectedTrackIds,
                                onCheckedChange = { checked -> onTogglePerson(person.trackId, checked) },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "شخص " + (index + 1) + " · " + personPosition(person.box),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "ثقة القناع " + (person.confidence * 100).toInt() + "٪",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "ID " + person.trackId,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                Text(
                    "إذا فقد النموذج التحديد أو تغيّر اتجاه الشاشة يعود التطبيق إلى طمس الجميع. قد يفوّت النموذج بعض الأشخاص.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

private fun personPosition(box: NormalizedPersonBox): String {
    val horizontal = when {
        box.centerX < 0.34f -> "يسار الشاشة"
        box.centerX > 0.66f -> "يمين الشاشة"
        else -> "وسط الشاشة"
    }
    val vertical = when {
        box.centerY < 0.34f -> "أعلى"
        box.centerY > 0.66f -> "أسفل"
        else -> "المنتصف"
    }
    return vertical + " · " + horizontal
}
