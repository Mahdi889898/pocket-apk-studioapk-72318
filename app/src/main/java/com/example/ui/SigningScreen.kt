package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.SigningProfile

@Composable
fun SigningScreen(
    profile: SigningProfile,
    onRegenerateKey: (String, String, String) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var cn by remember(profile.commonName) { mutableStateOf(profile.commonName) }
    var org by remember(profile.organization) { mutableStateOf(profile.organization) }
    var country by remember(profile.country) { mutableStateOf(profile.country) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column {
                                Text("گواهی دیجیتال امضای APK", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "RSA-2048 • APK Signature Scheme v1 + v2",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (profile.keystoreReady) {
                            InfoRow("صادرکننده (CN)", profile.commonName)
                            InfoRow("سازمان (O)", profile.organization)
                            InfoRow("اعتبار تا", profile.validUntil)

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "SHA-256 Fingerprint",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                            Text(
                                                text = profile.sha256Fingerprint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(profile.sha256Fingerprint))
                                            onShowMessage("اثر انگشت SHA-256 کپی شد.")
                                        },
                                        modifier = Modifier.testTag("copy_sha256_button")
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "کپی SHA-256")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Text("ساخت کلید امضای اختصاصی جدید", style = MaterialTheme.typography.titleMedium)
                        }

                        OutlinedTextField(
                            value = cn,
                            onValueChange = { cn = it },
                            label = { Text("نام توسعه‌دهنده (Common Name)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = org,
                                onValueChange = { org = it },
                                label = { Text("نام استودیو / سازمان") },
                                singleLine = true,
                                modifier = Modifier.weight(1.4f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedTextField(
                                value = country,
                                onValueChange = { country = it.take(2) },
                                label = { Text("کد کشور") },
                                singleLine = true,
                                modifier = Modifier.weight(0.6f),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Button(
                            onClick = { onRegenerateKey(cn, org, country) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("regenerate_key_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("تولید کلید RSA-2048 جدید")
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("نکات نصب خروجی APK روی گوشی", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "• تمام فایل‌های APK با امضای v1 و v2 و هم‌ترازی ۴ بایتی ساخته می‌شوند تا روی اندروید ۷ تا ۱۶ بدون خطای Parse نصب شوند.\n" +
                                "• پس از زدن دکمه «ذخیره APK» فایل را در پوشه Downloads ذخیره کنید، سپس در برنامه مدیریت فایل گوشی روی آن بزنید تا نصب شود.\n" +
                                "• پروژه‌های دارای فایل‌های خام .tsx یا importmap (مثل esm.sh) در اولین اجرا نیاز به اینترنت دارند، اما خروجی‌های باندل‌شده (dist/) و بازی‌های WebGL خالص کاملاً آفلاین اجرا می‌شوند.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
