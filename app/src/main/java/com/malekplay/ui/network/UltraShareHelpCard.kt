package com.malekplay.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun UltraShareHelpCard() {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("طريقة استخدام النقل — مساعدة", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "إخفاء الشرح" else "اضغط لعرض خطوات الإرسال والاستقبال")
            }
            if (expanded) {
                Text("استقبال الملفات على هذا الهاتف:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("1. افتح شاشة الاستقبال وانتظر ظهور رمز QR ورمز الاقتران.\n2. إذا لم يوجد راوتر مشترك، أنشئ نقطة اتصال MalekPlay وصِل الهاتف الآخر بالشبكة.\n3. امسح رمز QR من الهاتف المرسل؛ لن يبدأ النقل قبل موافقتك على طلب الاتصال.\n4. تُحفظ الملفات المستلمة في مجلد MalekPlay / Received عند دعم النظام للنشر العام.")
                Text("إرسال الملفات من هذا الهاتف:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("1. اختر أي نوع ملف أو مجلداً من مدير الملفات، باستثناء APK وPDF.\n2. اختر الجهاز من الأجهزة القريبة أو امسح QR المعروض على جهاز الاستقبال.\n3. انتظر قبول الاقتران، ثم ابدأ الإرسال وتابع سرعة النقل وتقدم كل جهاز.\n4. يمكن إعادة محاولة الملفات الفاشلة أو استئناف العناصر المتبقية من غرفة النقل.")
                Text("الاتصال الأساسي عبر شبكة Wi-Fi المحلية أو نقطة اتصال MalekPlay. Wi-Fi Direct مسار إضافي عند توفره. نقطة الاتصال المحلية مخصصة للنقل ولا توفر إنترنت. لا تشارك رمز الاقتران مع أشخاص غير موثوقين.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
