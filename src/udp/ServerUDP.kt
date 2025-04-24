package udp

import EquipmentList
import com.google.gson.Gson
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.io.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ServerUDP : Application() {
    private lateinit var campoPorta: TextField
    private lateinit var botaoIniciar: Button
    private lateinit var rotuloIpServidor: Label
    private lateinit var areaClientesConectados: TextArea
    private var socket: DatagramSocket? = null
    private var servidorRodando = false
    private val clientesConectados = ConcurrentHashMap<String, Long>()
    private val pingInterval = 10L
    private val timeout = 30L


    companion object {
        private lateinit var dto: EquipmentList

        @JvmStatic
        fun setDto(newDto: EquipmentList) {
            dto = newDto
        }

        @JvmStatic
        fun getDto(): EquipmentList {
            return dto
        }
    }

    override fun start(primarioStage: Stage) {
        primarioStage.title = "Servidor UDP"

        campoPorta = TextField().apply {
            promptText = "Porta"
        }

        botaoIniciar = Button("Iniciar Servidor").apply {
            setOnAction {
                logMessage("Servidor iniciando...")
                iniciarServidor()
                isDisable = true
            }
        }

        val botaoParar = Button("Parar Servidor").apply {
            setOnAction {
                pararServidor()
                botaoIniciar.isDisable = false
                logMessage("Servidor parando...")
            }
        }

        rotuloIpServidor = Label()

        areaClientesConectados = TextArea().apply {
            isEditable = false
            prefHeight = 300.0
        }

        val layout = VBox(10.0, campoPorta, botaoIniciar, botaoParar, rotuloIpServidor, areaClientesConectados)
        val cena = Scene(layout, 500.0, 400.0)
        primarioStage.scene = cena

        rotuloIpServidor.text = "IP do Servidor: ${InetAddress.getLocalHost().hostAddress}"

        primarioStage.setOnCloseRequest {
            logMessage("Fechamento da janela solicitado.")
            pararServidor()
            primarioStage.hide()
            logMessage("Servidor parado e janela oculta.")
        }

        primarioStage.show()
    }

    private fun enviarPings() {
        clientesConectados.keys.forEach { enderecoCliente ->
            try {
                val pacotePing =
                    DatagramPacket("PING".toByteArray(), "PING".length, InetAddress.getByName(enderecoCliente), campoPorta.text.toInt())
                socket?.send(pacotePing)
                logMessage("Ping enviado para $enderecoCliente")
            } catch (e: Exception) {
                logMessage("Erro ao enviar ping para $enderecoCliente: ${e.message}")
            }
        }
    }

    private fun verificarInatividade() {
        val agora = System.currentTimeMillis()
        val inativos = clientesConectados.filter { (endereco, ultimaAtividade) ->
            (agora - ultimaAtividade) > (timeout * 1000)
        }.keys

        inativos.forEach { enderecoCliente ->
            clientesConectados.remove(enderecoCliente)
            atualizarAreaClientesConectados()
            registrarClienteDesconectado(enderecoCliente)
        }
    }

    private fun registrarClienteConectado(enderecoCliente: String) {
        logMessage("Cliente conectado: $enderecoCliente")
    }

    private fun registrarClienteDesconectado(enderecoCliente: String) {
        logMessage("Cliente desconectado: $enderecoCliente")
    }

    private fun processarRequisicao(requisicao: String, enderecoCliente: String): String {
        val partes = requisicao.split(" ")
        if (partes.isEmpty()) {
            logMessage("Comando vazio recebido do cliente ($enderecoCliente)")
            return "Comando vazio"
        }
        val comando = partes[0]
        logMessage("Comando recebido do cliente ($enderecoCliente): $comando")

        val gson = Gson()

        return when (comando) {
            "get_all" -> {
                logMessage("Processando comando BUSCAR_LISTA...")
                try {
                    val jsonString = gson.toJson(dto)
                    logMessage(jsonString)
                    jsonString
                } catch (e: Exception) {
                    "Erro ao processar JSON: ${e.message}"
                }
            }
            "get" -> {
                logMessage("Processando comando BUSCAR_ITEM...")
                if (partes.size < 2) return "ID inválido"
                val id = partes[1].toIntOrNull() ?: return "ID inválido"
                try {
                    val equipamento = dto.equipments.find { it.id == id }
                    if (equipamento != null) {
                        logMessage(gson.toJson(equipamento))
                        gson.toJson(equipamento)
                    } else {
                        "Item não encontrado"
                    }
                } catch (e: Exception) {
                    "Erro ao processar JSON: ${e.message}"
                }
            }
            "ALTERAR_ITEM" -> {
                logMessage("Processando comando ALTERAR_ITEM...")
                if (partes.size < 3) return "Comando inválido"
                val id = partes[1].toIntOrNull() ?: return "ID inválido"
                val status = partes[2].toBooleanStrictOrNull() ?: return "Status inválido"
                return try {
                    val equipamentosAtualizados = dto.equipments.map {
                        if (it.id == id) it.copy(status = status) else it
                    }
                    val listaAtualizada = EquipmentList(equipamentosAtualizados)
                    salvarArquivoJson(listaAtualizada)
                    logMessage(gson.toJson(dto))
                    gson.toJson(dto)
                } catch (e: Exception) {
                    "Erro ao processar JSON: ${e.message}"
                }
            }
            "TESTE_CONEXAO" -> {
                logMessage("Processando comando TESTE_CONEXAO...")
                "CONEXAO_OK"
            }
            "PING" -> {
                logMessage("Processando comando PING...")
                "PONG"
            }
            else -> {
                logMessage("Comando inválido recebido do cliente ($enderecoCliente): $requisicao")
                "Comando inválido"
            }
        }
    }

    private fun salvarArquivoJson(listaEquipamentos: EquipmentList) {
        logMessage("Tentando salvar arquivo JSON...")
        try {
            val gson = Gson()
            val jsonString = gson.toJson(listaEquipamentos)
            val file = File("src/archives/eqJson.json")
            FileWriter(file).use { writer ->
                writer.write(jsonString)
            }
            dto = listaEquipamentos.copy()
        } catch (e: IOException) {
            logMessage("Erro ao salvar o arquivo JSON: ${e.message}")
        }
    }

    private fun iniciarServidor() {
        val porta = campoPorta.text.toIntOrNull() ?: return
        logMessage("Iniciando servidor na porta $porta...")
        socket = DatagramSocket(porta)
        servidorRodando = true
        logMessage("Servidor iniciado.")

        clientesConectados.clear()

        thread {
            val buffer = ByteArray(1024)
            while (servidorRodando) {
                try {
                    val pacote = DatagramPacket(buffer, buffer.size)
                    socket?.receive(pacote)
                    val enderecoCliente = pacote.address.hostAddress

                    clientesConectados[enderecoCliente] = System.currentTimeMillis()
                    atualizarAreaClientesConectados()
                    registrarClienteConectado(enderecoCliente)

                    val mensagemRecebida = String(pacote.data, 0, pacote.length).trim()
                    if (mensagemRecebida.isBlank()) {
                        logMessage("Mensagem vazia recebida de $enderecoCliente")
                        continue
                    }

                    logMessage("Mensagem recebida de $enderecoCliente: $mensagemRecebida")
                    val mensagemResposta = processarRequisicao(mensagemRecebida, enderecoCliente)
                    val pacoteResposta = DatagramPacket(
                        mensagemResposta.toByteArray(),
                        mensagemResposta.length,
                        pacote.address,
                        pacote.port
                    )
                    socket?.send(pacoteResposta)

                } catch (e: Exception) {
                    logMessage("Erro ao receber pacote: ${e.message}")
                }
            }
        }

        val scheduler = Executors.newScheduledThreadPool(1)
        scheduler.scheduleAtFixedRate({
            verificarInatividade()
            enviarPings()
        }, pingInterval, pingInterval, TimeUnit.SECONDS)
    }


    private fun pararServidor() {
        if (servidorRodando) {
            logMessage("Parando servidor...")
            servidorRodando = false
            socket?.close()
            logMessage("Porta do servidor fechada.")
            clientesConectados.clear()
            atualizarAreaClientesConectados()
            logMessage("Servidor parado.")
        } else {
            logMessage("Servidor não está em execução.")
        }
    }

    private fun atualizarAreaClientesConectados() {
        val infoClientes = clientesConectados.keys.joinToString("\n")
        javafx.application.Platform.runLater {
            areaClientesConectados.text = "Clientes Conectados:\n$infoClientes\n"
        }
    }

    private fun logMessage(message: String) {
        javafx.application.Platform.runLater {
            areaClientesConectados.appendText("$message\n")
        }
        println(message)
    }
}
fun main() {
    val pathArquivoJson = "src/archives/eqJson.json"
    val dto = carregarDTO(pathArquivoJson)
    ServerUDP.setDto(dto)
    Application.launch(ServerUDP::class.java)
}


private fun carregarDTO(path: String): EquipmentList {
    val file = File(path)
    if (!file.exists()) {
        throw FileNotFoundException("Arquivo JSON não encontrado: $path")
    }
    val inputStream = FileInputStream(file)
    val reader = InputStreamReader(inputStream)
    val gson = Gson()
    val json = gson.fromJson(reader, EquipmentList::class.java)
    reader.close()
    for (j in json.equipments) {
        println()
        println("Nome " + j.name)
        println("Id " + j.id)
        println("Status " + j.status)
    }
    return json
}
