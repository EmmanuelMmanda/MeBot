package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.AppCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderConnectionCard(ui: FeedUiState, onConnect:(AiConnection,String)->Unit,
    onModels:(AiConnection,String)->Unit, onOpenRouterStart:()->Unit, onOpenRouterFinish:(String,String)->Unit, onOpenRouterCancel:()->Unit) {
    val saved=ui.state?.connection ?: return
    var mode by rememberSaveable { mutableStateOf(saved.mode.takeUnless{it=="chatgpt"} ?: "direct") }
    var model by rememberSaveable { mutableStateOf(if(saved.mode=="chatgpt") AiProviders.defaultModel("direct") else saved.model) }
    var endpoint by rememberSaveable { mutableStateOf(saved.serviceUrl) }
    var localHttp by rememberSaveable { mutableStateOf(saved.allowLocalHttp) }
    var web by rememberSaveable { mutableStateOf(saved.webSearch) }
    // Credentials and short-lived authorization codes must never be saved with screen state.
    var key by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var chooseModel by rememberSaveable { mutableStateOf(false) }
    val local=mode=="compatible"
    val requiresKey= !local || !endpoint.startsWith("http://")
    val c=AiConnection(mode,model.trim(),if(mode in setOf("service","compatible"))endpoint.trim().trimEnd('/') else "",webSearch=web,allowLocalHttp=local && localHttp)
    val enabled=!ui.connecting && !ui.providerAuthBusy
    val labels=listOf("direct" to "OpenAI API","claude" to "Claude API","gemini" to "Gemini API","openrouter" to "OpenRouter","compatible" to stringResource(R.string.provider_personal),"service" to stringResource(R.string.feed_service))
    AppCard(modifier=Modifier.testTag("provider-connection")) {
        Text(stringResource(R.string.provider_choose),style=MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            labels.forEach { (id,label)->FilterChip(mode==id,onClick={ if(mode!=id){mode=id;model=AiProviders.defaultModel(id);endpoint="";key="";code="";localHttp=false;web=true;invalid=false;chooseModel=false;onOpenRouterCancel()} },enabled=enabled,label={Text(label)},modifier=Modifier.heightIn(min=48.dp).testTag("provider-$id")) }
        }
        Text(stringResource(when(mode){"claude"->R.string.provider_claude_note;"gemini"->R.string.provider_gemini_note;"compatible"->R.string.provider_local_note;"service"->R.string.feed_service_note;else->R.string.provider_cloud_note}),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(mode=="openrouter") {
            OutlinedButton(onClick=onOpenRouterStart,enabled=enabled&&!ui.openRouterPending,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("openrouter-sign-in")){Text(stringResource(R.string.provider_openrouter_signin))}
            if(ui.openRouterPending) {
                Text(stringResource(R.string.provider_openrouter_code_hint),style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(code,{code=it.take(2048)},label={Text(stringResource(R.string.provider_auth_code))},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().testTag("openrouter-code"))
                Row {
                    TextButton(onClick={onOpenRouterFinish(code.trim(),model.trim().ifBlank{"openrouter/auto"});code=""},enabled=code.isNotBlank()&&enabled){Text(stringResource(R.string.feed_check_connect))}
                    TextButton(onClick={code="";onOpenRouterCancel()}){Text(stringResource(R.string.cancel))}
                }
            }
        }
        if(mode in setOf("compatible","service")) {
            OutlinedTextField(endpoint,{endpoint=it.take(500);key="";invalid=false},label={Text(stringResource(R.string.provider_endpoint))},placeholder={Text(if(local)"https://your-server.example/v1" else "https://your-service.example")},singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().testTag("provider-endpoint"),enabled=enabled)
            if(local) Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Checkbox(localHttp,{localHttp=it;key=""},enabled=enabled,modifier=Modifier.testTag("provider-local-http"))
                Text(stringResource(R.string.provider_http_consent),style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f).padding(top=8.dp))
            }
        }
        if(!local || !endpoint.startsWith("http://")) {
            OutlinedTextField(key,{key=it.take(4096);invalid=false},label={Text(stringResource(if(local)R.string.provider_optional_key else if(mode=="service")R.string.feed_service_token else R.string.feed_key))},singleLine=true,visualTransformation=PasswordVisualTransformation(),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().testTag("connection-key"),enabled=enabled)
        } else Text(stringResource(R.string.provider_no_http_key),style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(model,{model=it.take(128);invalid=false},label={Text(stringResource(R.string.feed_model))},singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().testTag("provider-model"),enabled=enabled)
        if(mode in AiProviders.alternatives) {
            TextButton(onClick={onModels(c.copy(model=model.ifBlank{"catalog"}),key.ifBlank{"local-no-key"});chooseModel=true},enabled=enabled && (!requiresKey||key.isNotBlank()||local),modifier=Modifier.testTag("provider-load-models")){Text(stringResource(R.string.provider_load_models))}
        }
        if(mode in setOf("claude","openrouter")) Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Checkbox(web,{web=it},enabled=enabled)
            Text(stringResource(R.string.provider_web_note),style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f).padding(top=8.dp))
        }
        if(invalid)Text(stringResource(R.string.provider_invalid),color=MaterialTheme.colorScheme.error)
        Button(onClick={invalid=runCatching{AiProviders.validate(c);require(local||key.isNotBlank())}.isFailure;if(!invalid){onConnect(c,key.ifBlank{"local-no-key"});key=""}},enabled=enabled && model.isNotBlank() && (local || key.isNotBlank()),modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("connection-connect"),shape=RoundedCornerShape(16.dp)){
            Text(stringResource(if(ui.connecting)R.string.feed_connecting else R.string.feed_check_connect))
        }
    }
    if(chooseModel)AlertDialog(onDismissRequest={chooseModel=false},title={Text(stringResource(R.string.chatgpt_choose_model))},text={
        Column(Modifier.heightIn(max=350.dp).verticalScroll(rememberScrollState())) {
            if(ui.providerModelsLoading)Text(stringResource(R.string.provider_models_loading))
            ui.providerModelError?.let{FeedStatus(it)}
            ui.providerModels.forEach{slug->TextButton(onClick={model=slug;chooseModel=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(slug,modifier=Modifier.fillMaxWidth())}}
            if(!ui.providerModelsLoading && ui.providerModels.isEmpty() && ui.providerModelError==null)Text(stringResource(R.string.provider_model_manual))
        }
    },confirmButton={TextButton(onClick={chooseModel=false}){Text(stringResource(R.string.cancel))}})
}
