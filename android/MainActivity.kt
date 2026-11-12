package com.example.apsmonitoramentotermico

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import java.net.URLEncoder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max

private data class CityData(
    val name: String, val latitude: String, val longitude: String,
    var temp: Float = 0f, var humidity: Int = 0, var pressure: Int = 0,
    var wind: Float = 0f, var air: String = "Indisponível", var level: String = "SEM DADOS",
    var temperatures: List<Float> = emptyList(), var humidities: List<Float> = emptyList(),
    var times: List<String> = emptyList(), var collectedAt: String = "Aguardando consulta",
    var loaded: Boolean = false, var pm25: Double? = null
)

class MainActivity : AppCompatActivity() {
    private val cities = listOf(
        CityData("São Paulo - Centro", "-23.5505", "-46.6333"),
        CityData("São Bernardo do Campo", "-23.6914", "-46.5646"),
        CityData("Santo André", "-23.6639", "-46.5383"),
        CityData("São Caetano do Sul", "-23.6229", "-46.5548")
    )
    private val executor = Executors.newSingleThreadExecutor()
    private var loading = false
    private var loadError: String? = null
    private var selected = 0
    private var loggedIn = false
    private var sessionToken = ""
    // Emulador Android: 10.0.2.2 aponta para o computador que executa a API.
    private val apiBase = "http://10.0.2.2:8000"
    private var historyRecords = JSONArray()
    private var alertRecords = JSONArray()
    private var currentMeasurementId = -1
    private var apiMessage = ""
    private var currentScreen = 0
    private lateinit var root: LinearLayout
    private val navy = Color.rgb(19,37,63)
    private val blue = Color.rgb(37,99,235)
    private val muted = Color.rgb(101,116,139)
    private val bg = Color.rgb(243,247,253)
    private val green = Color.rgb(20,139,100)
    private val amber = Color.rgb(188,116,15)
    private val red = Color.rgb(198,60,68)
    private val data get() = cities[selected]
    private fun requestJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }
    private fun postCredentials(path: String, email: String, password: String, onDone: (String?)->Unit) {
        executor.execute {
            try {
                val conn = URL("$apiBase/$path").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.connectTimeout = 12000; conn.readTimeout = 12000
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val payload = JSONObject().put("email", email).put("senha", password).toString()
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code !in 200..299) throw Exception("HTTP $code: $body")
                val result = JSONObject(body)
                if (path == "login") sessionToken = result.getString("token")
                runOnUiThread { onDone(null) }
            } catch (e: Exception) { runOnUiThread { onDone(e.message ?: "Falha de rede") } }
        }
    }
    private fun apiArray(path: String): JSONArray {
        val conn = URL("$apiBase/$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 15000
        if (sessionToken.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $sessionToken")
        try {
            if (conn.responseCode !in 200..299) throw Exception("API HTTP ${conn.responseCode}")
            return JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }
    private fun loadCity(force: Boolean = false) {
        val city = data
        if (loading || (city.loaded && !force)) return
        loading = true; loadError = null
        if (loggedIn) renderCurrent()
        executor.execute {
            try {
                val cityQuery = URLEncoder.encode(city.name, "UTF-8")
                val list = apiArray("monitoramentos?cidade=$cityQuery")
                if (list.length() == 0) throw Exception("Cidade sem medições")
                val now = list.getJSONObject(0)
                val history = apiArray("historico?cidade=$cityQuery&limite=24")
                val alerts = apiArray("alertas?cidade=$cityQuery")
                val recent = (0 until history.length()).map { history.getJSONObject(it) }.reversed()
                runOnUiThread {
                    city.temp = now.getDouble("temperatura").toFloat()
                    city.humidity = now.getInt("umidade")
                    city.pressure = now.getDouble("pressao").toInt()
                    city.wind = now.getDouble("vento").toFloat()
                    city.pm25 = if (now.isNull("pm25")) null else now.getDouble("pm25")
                    city.air = now.optString("qualidade_ar", "Indisponível")
                    city.level = now.optString("classificacao", "SEM DADOS")
                    city.collectedAt = now.optString("data_hora", "")
                    city.temperatures = recent.map { it.optDouble("temperatura",0.0).toFloat() }
                    city.humidities = recent.map { it.optDouble("umidade",0.0).toFloat() }
                    city.times = recent.map { it.optString("data_hora", "") }
                    city.loaded = true
                    historyRecords = history; alertRecords = alerts
                    currentMeasurementId = now.optInt("id", -1)
                    loading = false; apiMessage = "API REST + banco de dados • ${city.collectedAt}"
                    if (data === city && loggedIn) renderCurrent()
                    else if (loggedIn && !data.loaded) loadCity()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false; loadError = e.message ?: "Falha de conexão"
                    if (data === city && loggedIn) renderCurrent()
                    else if (loggedIn && !data.loaded) loadCity()
                }
            }
        }
    }
    private fun renderCurrent() {
        when (currentScreen) {
            1 -> showMonitoring(); 2 -> showHistory(); 3 -> showMap()
            4 -> showAlerts(); 5 -> showDetails(); 6 -> showAbout()
            else -> showHome()
        }
    }
    private fun ensureLoaded() {
        if (!data.loaded && !loading && loadError == null) loadCity()
    }
    private fun dataNotice(p: LinearLayout) {
        panel(p) {
            val message = when {
                loading && !data.loaded -> "Consultando fontes meteorológicas..."
                loadError != null && !data.loaded -> "Falha na consulta: $loadError"
                !data.loaded -> "Dados não carregados. Conecte o emulador à internet."
                else -> "Dados reais via API e banco • ${data.collectedAt} • Open-Meteo"
            }
            addText(it,message,13f,muted,false,0)
        }
        if (loadError != null || data.loaded) action(p,"Atualizar dados reais") { loadCity(true) }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showLogin() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(v: Float) = String.format(java.util.Locale("pt","BR"),"%.1f",v)
    private fun shape(color: Int, radius: Int = 18, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (border != null) setStroke(dp(1),border)
    }
    private fun label(value: String, size: Float = 15f, color: Int = navy, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null,Typeface.BOLD)
    }
    private fun addText(parent: LinearLayout, value: String, size: Float = 15f, color: Int = navy, bold: Boolean = false, bottom: Int = 7): TextView {
        val v = label(value,size,color,bold)
        parent.addView(v,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(bottom) }); return v
    }
    private fun base(title: String, subtitle: String = "Projeto acadêmico • API REST") {
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg) }
        setContentView(root)
        val top = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(26),dp(22),dp(18)); background=shape(navy,0) }
        addText(top,"◉  ATMOSFERA  |  APS",12f,Color.rgb(148,191,255),true,10)
        addText(top,title,26f,Color.WHITE,true,5)
        addText(top,subtitle,11f,Color.rgb(195,211,232),false,0)
        root.addView(top)
    }
    private fun scroll(block: (LinearLayout)->Unit) {
        val s = ScrollView(this).apply { isFillViewport=false; clipToPadding=false }
        val body = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(20)) }
        s.addView(body); root.addView(s,LinearLayout.LayoutParams(-1,0,1f)); block(body)
    }
    private fun panel(parent: LinearLayout, block: (LinearLayout)->Unit) {
        val p = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(17),dp(16),dp(17),dp(16)); background=shape(Color.WHITE,18,Color.rgb(229,235,245)); elevation=dp(2).toFloat() }
        parent.addView(p,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(13) }); block(p)
    }
    private fun stat(parent: LinearLayout, title: String, value: String, symbol: String) = panel(parent) {
        addText(it,"$symbol  $title",13f,muted,false,6); addText(it,value,25f,navy,true,0)
    }
    private fun action(parent: LinearLayout, title: String, click: ()->Unit) {
        val b = Button(this).apply { text=title; isAllCaps=false; textSize=14f; setTextColor(Color.WHITE); background=shape(blue,13); setOnClickListener { click() } }
        parent.addView(b,LinearLayout.LayoutParams(-1,dp(50)).apply { bottomMargin=dp(10) })
    }
    private fun cityPicker(parent: LinearLayout, refresh: ()->Unit) {
        addText(parent,"LOCAL MONITORADO",12f,muted,true,8)
        val picker = Spinner(this)
        picker.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,cities.map { it.name })
        picker.setSelection(selected)
        picker.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
            private var initial=true
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if(initial) { initial=false; if(position==selected) return }
                if(selected!=position) { selected=position; refresh() }
            }
        }
        panel(parent) { it.addView(picker) }
    }
    private fun status(parent: LinearLayout) {
        val c=when(data.level) { "NORMAL"->green; "ATENÇÃO"->amber; "ALERTA"->red; else->muted }
        panel(parent) {
            addText(it,"CONDIÇÃO AMBIENTAL • ${data.name}",12f,muted,true,8)
            addText(it,"● ${data.level}",27f,c,true,6)
            addText(it,"Classificação experimental por PM2.5; não confirma inversão térmica.",12f,muted,false,0)
        }
    }
    private fun charts(parent: LinearLayout) {
        panel(parent) {
            addText(it,"Temperatura • últimas 6 amostras",16f,navy,true)
            it.addView(LineChart(data.temperatures,blue),LinearLayout.LayoutParams(-1,dp(145)))
            addText(it,"Medições armazenadas na API em °C",11f,muted,false,0)
        }
        panel(parent) {
            addText(it,"Umidade relativa • últimas 6 amostras",16f,navy,true)
            it.addView(LineChart(data.humidities,green),LinearLayout.LayoutParams(-1,dp(145)))
            addText(it,"Medições armazenadas na API em %",11f,muted,false,0)
        }
    }
    private fun metrics(parent: LinearLayout) {
        stat(parent,"Temperatura", "${fmt(data.temp)} °C","🌡")
        stat(parent,"Umidade relativa", "${data.humidity}%","💧")
        stat(parent,"Pressão atmosférica", "${data.pressure} hPa","◉")
        stat(parent,"Velocidade do vento", "${fmt(data.wind)} km/h","➤")
        stat(parent,"Qualidade do ar (PM2.5)",if(data.pm25 != null) "${fmt(data.pm25!!.toFloat())} µg/m³ • ${data.air}" else "Indisponível","☁")
    }
    private fun nav(active: Int) {
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(dp(3),dp(6),dp(3),dp(8)); setBackgroundColor(Color.WHITE) }
        listOf("⌂\nInício","◉\nMedições","▥\nHistórico","⌖\nMapa","!\nAlertas").forEachIndexed { index,name ->
            val t=label(name,11f,if(index==active) blue else muted,index==active).apply { gravity=Gravity.CENTER; setPadding(0,dp(4),0,dp(4)); setOnClickListener {
                when(index) {0->showHome();1->showMonitoring();2->showHistory();3->showMap();else->showAlerts()}
            } }
            bar.addView(t,LinearLayout.LayoutParams(0,dp(51),1f))
        }
        root.addView(bar)
    }
    private fun showLogin() {
        base("Monitoramento Térmico","Aplicativo Android • Trabalho APS")
        scroll { p ->
            panel(p) { addText(it,"Bem-vindo!",23f,navy,true); addText(it,"Acompanhe indicadores ambientais urbanos e condições associadas à reversão térmica.",15f,muted,false,0) }
            addText(p,"ACESSO DEMONSTRATIVO",12f,muted,true)
            val email=EditText(this).apply { hint="E-mail"; inputType=33; setSingleLine(true) }
            val pass=EditText(this).apply { hint="Senha"; inputType=129; setSingleLine(true) }
            panel(p) { it.addView(email); it.addView(pass) }
            action(p,"Entrar no painel") {
                if(email.text.isBlank() || pass.text.isBlank()) Toast.makeText(this,"Preencha e-mail e senha",Toast.LENGTH_SHORT).show()
                else postCredentials("login",email.text.toString().trim(),pass.text.toString()) { err ->
                    if(err != null) Toast.makeText(this,"Login: $err",Toast.LENGTH_LONG).show()
                    else { loggedIn=true; showHome() }
                }
            }
            action(p,"Criar conta") {
                if(email.text.isBlank() || pass.text.length < 6) Toast.makeText(this,"Informe e-mail e senha de pelo menos 6 caracteres",Toast.LENGTH_LONG).show()
                else postCredentials("cadastro",email.text.toString().trim(),pass.text.toString()) { err ->
                    Toast.makeText(this,if(err==null) "Conta criada! Agora entre." else "Cadastro: $err",Toast.LENGTH_LONG).show()
                }
            }
            addText(p,"Login e cadastro reais pela API REST. Inicie o servidor no computador.",12f,muted)
        }
    }
    private fun showHome() {
        currentScreen = 0
        base("Painel ambiental", "Dados meteorológicos: Open-Meteo via API REST")
        scroll { p ->
            cityPicker(p) { loadError=null; showHome(); ensureLoaded() }; dataNotice(p); if (data.loaded) status(p)
            if (data.loaded) panel(p) { addText(it,"RESUMO DE ${data.name.uppercase()}",12f,muted,true); addText(it,"${fmt(data.temp)} °C     •     ${data.humidity}% umidade",22f,navy,true,0) }
            if (data.loaded) charts(p)
            action(p,"Ver todas as medições") { showMonitoring() }
            action(p,"Entenda a reversão térmica") { showAbout() }
            action(p,"Sair da conta") { loggedIn=false; showLogin() }
        }
        nav(0)
        ensureLoaded()
    }
    private fun showMonitoring() {
        currentScreen = 1
        base("Monitoramento")
        scroll { p -> cityPicker(p) { loadError=null; showMonitoring(); ensureLoaded() }; dataNotice(p); if (data.loaded) { status(p); metrics(p) }
            action(p,"Detalhes desta medição") { showDetails() }
        }; nav(1); ensureLoaded()
    }
    private fun showDetails() {
        currentScreen = 5
        base("Detalhes da medição")
        scroll { p ->
            panel(p) {
                addText(it,"Local: ${data.name}",18f,navy,true)
                addText(it,"Latitude: ${data.latitude}  •  Longitude: ${data.longitude}",13f,muted)
                addText(it,"Horário do modelo: ${data.collectedAt}",13f,muted)
                addText(it,"Classificação: ${data.level}",16f,navy,true,0)
            }
            metrics(p); action(p,"Voltar ao monitoramento") { showMonitoring() }
        }; nav(1)
    }
    private fun showHistory() {
        currentScreen = 2
        base("Histórico de medições")
        scroll { p ->
            cityPicker(p) { loadError=null; showHistory(); ensureLoaded() }; dataNotice(p)
            addText(p,"MEDIÇÕES SALVAS NO BANCO DE DADOS",12f,muted,true)
            data.temperatures.forEachIndexed { i,v ->
                panel(p) {
                    addText(it,"${data.times.getOrNull(i) ?: "Hora não disponível"}",13f,muted)
                    addText(it,"${fmt(v)} °C    |    ${data.humidities[i].toInt()}% umidade",19f,navy,true,0)
                }
            }
            if (data.loaded) charts(p)
            addText(p,"Histórico persistido no banco de dados pela API REST. Atualize em horários diferentes para registrar novas medições.",12f,muted)
        }; nav(2); ensureLoaded()
    }
    private fun showMap() {
        currentScreen = 3
        base("Pontos monitorados","Localizações geográficas • Abrir no Google Maps")
        scroll { p ->
            cities.forEachIndexed { index,city ->
                panel(p) {
                    addText(it,"⌖ ${city.name}",18f,navy,true)
                    addText(it,"${city.latitude}, ${city.longitude}",12f,muted)
                    addText(it,if(city.loaded) "${fmt(city.temp)} °C  •  ${city.level}" else "Dados ainda não consultados",15f,if(city.level=="ALERTA") red else navy,true,5)
                    val b=Button(this).apply { text="Selecionar este ponto"; isAllCaps=false; setOnClickListener { selected=index; loadError=null; showMonitoring(); ensureLoaded() } }
                    it.addView(b)
                    val mapButton=Button(this).apply {
                        text="Abrir no Google Maps"; isAllCaps=false
                        setOnClickListener {
                            val uri=Uri.parse("https://www.google.com/maps/search/?api=1&query=${city.latitude}%2C${city.longitude}")
                            startActivity(Intent(Intent.ACTION_VIEW,uri))
                        }
                    }
                    it.addView(mapButton)
                }
            }
            addText(p,"O mapa abre no Google Maps; a incorporação do SDK ao aplicativo requer chave de API.",12f,muted)
        }; nav(3)
    }
    private fun showAlerts() {
        currentScreen = 4
        base("Alertas ambientais")
        scroll { p ->
            cityPicker(p) { loadError=null; showAlerts(); ensureLoaded() }; dataNotice(p); if (data.loaded) status(p)
            addText(p,"HISTÓRICO DE ALERTAS SALVOS",12f,muted,true)
            if (alertRecords.length() == 0) panel(p) { addText(it,"Nenhum alerta registrado para esta cidade.",14f,muted,false,0) }
            for (i in 0 until alertRecords.length()) {
                val item=alertRecords.getJSONObject(i)
                panel(p) {
                    addText(it,item.optString("nivel"),18f,if(item.optString("nivel")=="ALERTA") red else amber,true)
                    addText(it,item.optString("mensagem"),14f,navy)
                    addText(it,item.optString("data_hora"),12f,muted,false,0)
                }
            }
            addText(p,"Critério experimental por PM2.5: até 15 Normal; acima de 15 Atenção; acima de 35 Alerta (µg/m³). Quando indisponível: Sem dados. Não diagnostica inversão térmica nem substitui alertas oficiais.",12f,muted)
            action(p,"Sobre a reversão térmica") { showAbout() }
        }; nav(4); ensureLoaded()
    }
    private fun showAbout() {
        currentScreen = 6
        base("Reversão térmica","Informações educativas")
        scroll { p ->
            panel(p) {
                addText(it,"O que é inversão térmica?",20f,navy,true)
                addText(it,"Ocorre quando uma camada de ar quente fica sobre uma camada de ar mais frio próxima à superfície, dificultando a circulação vertical do ar e favorecendo o acúmulo de poluentes.",15f,navy,false,0)
            }
            panel(p) {
                addText(it,"Por que monitorar?",18f,navy,true)
                addText(it,"Temperatura, umidade, pressão, vento e qualidade do ar ajudam a acompanhar as condições atmosféricas urbanas. Esses indicadores, isoladamente, não comprovam uma inversão térmica.",15f,navy,false,0)
            }
            action(p,"Voltar ao painel") { showHome() }
        }; nav(0)
    }

    private inner class LineChart(private val values: List<Float>, private val lineColor: Int): View(this) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if(values.size<2) return
            val left=dp(12).toFloat(); val right=width-dp(12).toFloat()
            val top=dp(20).toFloat(); val bottom=height-dp(25).toFloat()
            val low=values.minOrNull()!!-2f; val high=values.maxOrNull()!!+2f
            paint.strokeWidth=dp(1).toFloat(); paint.color=Color.rgb(227,234,244)
            for(i in 0..3) { val y=top+(bottom-top)*i/3f; canvas.drawLine(left,y,right,y,paint) }
            val path=Path()
            values.forEachIndexed { i,v ->
                val x=left+(right-left)*i/(values.size-1)
                val y=bottom-(v-low)/max(1f,high-low)*(bottom-top)
                if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            paint.color=lineColor; paint.strokeWidth=dp(3).toFloat(); paint.style=Paint.Style.STROKE
            canvas.drawPath(path,paint); paint.style=Paint.Style.FILL
            values.forEachIndexed { i,v ->
                val x=left+(right-left)*i/(values.size-1)
                val y=bottom-(v-low)/max(1f,high-low)*(bottom-top)
                canvas.drawCircle(x,y,dp(4).toFloat(),paint)
                paint.textSize=dp(10).toFloat(); paint.textAlign=Paint.Align.CENTER
                canvas.drawText(fmt(v),x,if(y<top+dp(14)) y+dp(18) else y-dp(10),paint)
            }
        }
    }
}
