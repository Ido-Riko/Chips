package com.chips.pantry

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.delay

@Composable
internal fun PantryScreen(state: PantryState, vm: PantryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    var adding by rememberSaveable { mutableStateOf(false) }
    var receipt by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var adjusting by remember { mutableStateOf<PantryItem?>(null) }
    var scanError by remember { mutableStateOf("") }
    BackHandler { when { adding -> adding = false; receipt -> receipt = false; adjusting != null -> adjusting = null; else -> onBack() } }
    LaunchedEffect(Unit) { while(true) { vm.refresh(); delay(3000) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if(uri != null) { receipt = true; vm.importPhoto(uri) } }
    LazyColumn(Modifier.fillMaxSize().testTag("pantry-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick = onBack) { Text("‹ Today") }; Text("Your pantry", style = MaterialTheme.typography.headlineLarge) }
        item {
            Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { code = ""; adding = true }, modifier = Modifier.testTag("pantry-add")) { Text("Add item") }
                OutlinedButton(onClick = { receipt = true }) { Text("Receipt") }
                TextButton(onClick = vm::sync, enabled = !state.busy) { Text("Sync") }
            }
            OutlinedButton(onClick = {
                scanError = ""
                try { GmsBarcodeScanning.getClient(context).startScan()
                    .addOnSuccessListener { value -> value.rawValue?.let { code = it; adding = true } }
                    .addOnFailureListener { scanError = "Scanner unavailable. Use Add item to enter the barcode manually." }
                } catch(_: Exception) { scanError = "Scanner unavailable. Enter the barcode manually." }
            }, enabled = !state.busy) { Text("Scan barcode") }
            if(scanError.isNotBlank()) Text(scanError)
            if(state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
            Text("Meal ideas come from your main CHIPS assistant using the server's last synced pantry.", style = MaterialTheme.typography.bodySmall)
        }
        if(state.items.isEmpty()) item { Text("Add groceries to see what you have. Barcode and receipt entries always need your confirmation.") }
        items(state.items, key = { it.key }) { item ->
            Card(modifier = Modifier.fillMaxWidth(), onClick = { adjusting = item }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("${item.quantity.stripTrailingZeros().toPlainString()} ${item.unit}")
                    }
                    if(item.pack.isNotBlank()) Text("Package: ${item.pack}", style = MaterialTheme.typography.bodySmall)
                    if(item.expiry.isNotBlank()) Text("Expiry: ${item.expiry}" + if(LocalDate.parse(item.expiry).isBefore(LocalDate.now())) " · expired" else "", style = MaterialTheme.typography.bodySmall)
                    if(item.quantity <= item.low) Text(if(item.quantity.signum() < 0) "Stock needs checking · overlapping changes" else "Running low", color = MaterialTheme.colorScheme.tertiary)
                    Text("Tap to use or correct stock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { TextButton(onClick = { links.openUri("https://world.openfoodfacts.org/data") }) { Text("Open Food Facts · ODbL") } }
    }
    if(adding) ItemDialog(code, state, vm, onDismiss = { adding = false })
    if(receipt) {
        var allowDuplicate by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { if(!state.busy) receipt = false }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Review receipt", style = MaterialTheme.typography.titleLarge)
                    Text("One purchase per line: name | quantity | unit. Use item, g, kg, ml or l. Check amounts carefully; prices are not quantities.")
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.busy) { Text(if(state.hasPhoto) "Replace photo" else "Choose receipt photo") }
                    if(state.hasPhoto) OutlinedButton(onClick = vm::readReceipt, enabled = !state.busy) { Text("Read photo on server") }
                    OutlinedTextField(value = state.receipt, onValueChange = { if(it.length <= 12000) vm.saveDraft(it) }, label = { Text("Purchase lines") },
                        placeholder = { Text("Rice | 1 | kg\nMilk 1 L | 2 | item") }, minLines = 4, modifier = Modifier.fillMaxWidth().testTag("receipt-draft"), enabled = !state.busy)
                    Text("Drafts stay on this phone. The photo is sent only when you tap Read; it is not retained by CHIPS on the server. Confirming or discarding removes the local photo.", style = MaterialTheme.typography.bodySmall)
                    if(state.duplicate) {
                        Text("This receipt appears to have been added already.", color = MaterialTheme.colorScheme.error)
                        Row { Checkbox(allowDuplicate, { allowDuplicate = it }); Text("This is another purchase") }
                    }
                    if(state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
                    if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Button(onClick = { vm.confirmReceipt(allowDuplicate) }, enabled = !state.busy && state.receipt.isNotBlank() && (!state.duplicate || allowDuplicate), modifier = Modifier.testTag("receipt-confirm")) { Text("Add reviewed items") }
                    Row { TextButton(onClick = vm::discard, enabled = !state.busy) { Text("Discard draft") }; TextButton(onClick = { receipt = false }, enabled = !state.busy) { Text("Close") } }
                }
            }
        }
    }
    adjusting?.let { item ->
        var amount by remember(item.key) { mutableStateOf("1") }
        var consume by remember(item.key) { mutableStateOf(true) }
        var name by remember(item.key) { mutableStateOf(item.name) }
        var low by remember(item.key) { mutableStateOf(item.low.toPlainString()) }
        var editError by remember(item.key) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if(!state.busy) adjusting = null }, title = { Text(item.name) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Current stock: ${item.quantity.stripTrailingZeros().toPlainString()} ${item.unit}")
                Row { FilterChip(consume, { consume = true }, label = { Text("Use") }); Spacer(Modifier.width(8.dp)); FilterChip(!consume, { consume = false; amount = item.quantity.max(BigDecimal.ZERO).toPlainString() }, label = { Text("Correct total") }) }
                OutlinedTextField(amount, { amount = it }, label = { Text(if(consume) "Amount used (${item.unit})" else "Actual stock (${item.unit})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if(!consume) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") })
                    OutlinedTextField(low, { low = it }, label = { Text("Low-stock level (${item.unit})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                if(editError.isNotEmpty()) Text(editError, color = MaterialTheme.colorScheme.error)
                Text("Set actual stock to 0 to remove this item from the pantry. Purchase and correction history stays saved.", style = MaterialTheme.typography.bodySmall)
                if(state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { try { vm.adjust(item.copy(name = name, low = decimal(low)), amount, consume) { adjusting = null } } catch(e: Exception) { editError = e.message ?: "Check the low-stock level" } }, enabled = !state.busy) { Text("Save") } }, dismissButton = { TextButton(onClick = { adjusting = null }, enabled = !state.busy) { Text("Cancel") } })
    }
}

@Composable
private fun ItemDialog(initialCode: String, state: PantryState, vm: PantryViewModel, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf(initialCode) }
    var pack by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf("item") }
    var expiry by rememberSaveable { mutableStateOf("") }
    var low by rememberSaveable { mutableStateOf("0") }
    LaunchedEffect(initialCode) { if(initialCode.isNotEmpty()) vm.lookup(initialCode) { n, p -> name = n; pack = p } }
    Dialog(onDismissRequest = { if(!state.busy) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add groceries", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(code, { code = it }, label = { Text("Barcode (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                if(code.isNotBlank()) TextButton(onClick = { vm.lookup(code) { n,p -> name=n; pack=p } }, enabled = !state.busy) { Text("Look up product") }
                OutlinedTextField(name, { name = it }, label = { Text("Product name") }, modifier = Modifier.fillMaxWidth().testTag("pantry-name"), enabled = !state.busy)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { pantryUnits.forEach { u -> FilterChip(unit == u, { unit = u }, label = { Text(u) }, enabled = !state.busy) } }
                OutlinedTextField(pack, { pack = it }, label = { Text("Package size (optional, e.g. 500 g)") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                Text("For two 500 g packets, use 2 item and package size 500 g.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(expiry, { expiry = it }, label = { Text("Expiry (optional, YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                OutlinedTextField(low, { low = it }, label = { Text("Low-stock level ($unit)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                if(state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancel") }; Button(onClick = { vm.add(name, code, unit, pack, expiry, quantity, low, onDismiss) }, enabled = !state.busy, modifier = Modifier.testTag("pantry-save")) { Text("Add to pantry") } }
            }
        }
    }
}
