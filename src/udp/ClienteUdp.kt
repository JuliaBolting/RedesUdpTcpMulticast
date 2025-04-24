package udp

import org.json.JSONObject
import org.json.JSONArray
import javafx.application.Application
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.Stage
import org.json.JSONException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

class ClienteUdp : Application() {
    private lateinit var areaLog: TextArea
    private lateinit var campoIpServidor: TextField
    private lateinit var campoPortaServidor: TextField
    private lateinit var botaoConectar: Button
    private lateinit var botaoAtualizarLista: Button
    private lateinit var containerListaEquipamentos: VBox
    private var socket: DatagramSocket? = null
    private var conectado = false

    override fun start(estagioPrincipal: Stage) {
        estagioPrincipal.title = "Cliente UDP"

        areaLog = criarAreaLog()
        campoIpServidor = criarCampoTexto("ex.: 192.168.1.1")
        campoPortaServidor = criarCampoTexto("ex.: 12345")
        campoIpServidor.text = InetAddress.getLocalHost().hostAddress
        botaoConectar = criarBotaoConectar()
        botaoAtualizarLista = criarBotaoAtualizarLista()
        containerListaEquipamentos = criarContainerListaEquipamentos()

        val layout = VBox(
            10.0,
            Label("Digite o IP do Servidor:"), campoIpServidor,
            Label("Digite a Porta do Servidor:"), campoPortaServidor,
            botaoConectar, botaoAtualizarLista,
            Label("Lista de Equipamentos:"), ScrollPane(containerListaEquipamentos),
            areaLog
        )
        val cena = Scene(layout, 800.0, 500.0)
        estagioPrincipal.scene = cena
        estagioPrincipal.show()

        estagioPrincipal.setOnCloseRequest { tratarFechamentoJanela() }
    }

    private fun criarAreaLog(): TextArea {
        return TextArea().apply { isEditable = false }
    }

    private fun criarCampoTexto(prompt: String): TextField {
        return TextField().apply { promptText = prompt }
    }

    private fun criarBotaoConectar(): Button {
        return Button("Conectar ao Servidor").apply {
            setOnAction {
                if (conectado) desconectarDoServidor() else conectarAoServidor()
            }
        }
    }

    private fun criarBotaoAtualizarLista(): Button {
        return Button("Atualizar Lista").apply {
            setOnAction {
                if (conectado) buscarListaEquipamentos() else registrarErro("Não conectado a nenhum servidor.")
            }
        }
    }

    private fun criarContainerListaEquipamentos(): VBox {
        return VBox(10.0).apply {
            setPrefSize(600.0, 300.0)
        }
    }

    private fun tratarFechamentoJanela() {
        areaLog.appendText("Fechamento da janela solicitado. Tentando desconectar do servidor...\n")
        println("Fechamento da janela solicitado. Tentando desconectar do servidor...")
        desconectarDoServidor()
        areaLog.appendText("Aplicação encerrada.\n")
        println("Aplicação encerrada.")
    }

    private fun conectarAoServidor() {
        val ipServidor = campoIpServidor.text
        val portaServidor = campoPortaServidor.text.toIntOrNull() ?: return registrarErro("IP ou Porta inválidos.")

        try {
            areaLog.appendText("Tentando conectar ao servidor em $ipServidor:$portaServidor...\n")
            println("Tentando conectar ao servidor em $ipServidor:$portaServidor...")
            socket = DatagramSocket().apply {
                connect(InetAddress.getByName(ipServidor), portaServidor)
            }

            val mensagemTeste = "TESTE_CONEXAO"
            val pacoteTeste = DatagramPacket(
                mensagemTeste.toByteArray(),
                mensagemTeste.length,
                InetAddress.getByName(ipServidor),
                portaServidor
            )
            socket?.send(pacoteTeste)

            socket?.soTimeout = 3000

            val buffer = ByteArray(1024)
            val pacoteResposta = DatagramPacket(buffer, buffer.size)
            socket?.receive(pacoteResposta)
            val mensagemResposta = String(pacoteResposta.data, 0, pacoteResposta.length).trim()

            if (mensagemResposta == "CONEXAO_OK") {
                conectado = true
                botaoConectar.text = "Desconectar do Servidor"
                areaLog.appendText("Conectado ao servidor em $ipServidor:$portaServidor\n")
                println("Conectado ao servidor em $ipServidor:$portaServidor")
            } else {
                registrarErro("Resposta inesperada do servidor: $mensagemResposta")
                desconectarDoServidor()
            }
        } catch (e: Exception) {
            registrarErro("Erro ao conectar ao servidor: ${e.message}")
        }
    }

    private fun desconectarDoServidor() {
        try {
            if (conectado) {
                areaLog.appendText("Tentando desconectar do servidor...\n")
                println("Tentando desconectar do servidor...")
                socket?.close()
                conectado = false
                botaoConectar.text = "Conectar ao Servidor"
                areaLog.appendText("Desconectado do servidor.\n")
                println("Desconectado do servidor.")
                containerListaEquipamentos.children.clear()
            } else {
                registrarErro("Já desconectado ou não conectado.")
            }
        } catch (e: Exception) {
            registrarErro("Erro ao desconectar: ${e.message}")
        }
    }

    private fun buscarListaEquipamentos() {
        val ipServidor = campoIpServidor.text
        val portaServidor = campoPortaServidor.text.toIntOrNull() ?: return

        val pedido = "get_all"
        val pacote =
            DatagramPacket(pedido.toByteArray(), pedido.length, InetAddress.getByName(ipServidor), portaServidor)
        try {
            areaLog.appendText("Enviando pedido get_all para o servidor em $ipServidor:$portaServidor...\n")
            socket?.send(pacote)

            val buffer = ByteArray(1024)
            val pacoteResposta = DatagramPacket(buffer, buffer.size)
            socket?.receive(pacoteResposta)
            val dados = String(pacoteResposta.data, 0, pacoteResposta.length, StandardCharsets.UTF_8)

            areaLog.appendText("Resposta recebida do servidor: $dados\n")
            println("Resposta recebida do servidor: $dados")

            // Verificando se a resposta é um JSONObject
            try {
                val jsonObject = JSONObject(dados)
                val jsonArray = jsonObject.getJSONArray("equipments")
                val listaEquipamentos = mutableListOf<Equipment>()

                for (i in 0 until jsonArray.length()) {
                    val jsonEquipamento = jsonArray.getJSONObject(i)
                    val id = jsonEquipamento.getInt("id")
                    val name = jsonEquipamento.getString("name")
                    val status = jsonEquipamento.getBoolean("status")
                    listaEquipamentos.add(Equipment(id, name, status))
                }
                atualizarInterfaceListaEquipamentos(listaEquipamentos)
            } catch (e: JSONException) {
                registrarErro("Erro ao interpretar JSON: ${e.message}")
            }
        } catch (e: Exception) {
            registrarErro("Erro ao buscar lista de equipamentos: ${e.message}")
        }
    }

    private fun buscarDetalhesEquipamento(id: Int) {
        val ipServidor = campoIpServidor.text
        val portaServidor = campoPortaServidor.text.toIntOrNull() ?: return

        val pedido = "get $id"
        println("Pacote para enviar ao servidor: "+ pedido)
        val pacote =
            DatagramPacket(pedido.toByteArray(), pedido.length, InetAddress.getByName(ipServidor), portaServidor)
        try {
            areaLog.appendText("Enviando pedido get para ID: $id para o servidor em $ipServidor:$portaServidor...\n")
            socket?.send(pacote)

            val buffer = ByteArray(1024)
            val pacoteResposta = DatagramPacket(buffer, buffer.size)
            socket?.receive(pacoteResposta)
            areaLog.appendText("Detalhes do equipamento ID: $id recebidos do servidor.\n")

            val dados = String(pacoteResposta.data, 0, pacoteResposta.length, StandardCharsets.UTF_8)
            println("Dados recebidos do servidor: $dados")

            // Convertendo o JSON recebido em um JSONObject
            val jsonObject = JSONObject(dados)
            val equipamento = Equipment(
                id = jsonObject.getInt("id"),
                name = jsonObject.getString("name"),
                status = jsonObject.getBoolean("status")
            )
            atualizarInterfaceListaEquipamentos(listOf(equipamento))
        } catch (e: Exception) {
            registrarErro("Erro ao buscar detalhes do equipamento: ${e.message}")
        }
    }

    private fun alterarStatusEquipamento(id: Int, novoStatus: Boolean) {
        val ipServidor = campoIpServidor.text
        val portaServidor = campoPortaServidor.text.toIntOrNull() ?: return

        val pedido = "ALTERAR_ITEM $id $novoStatus"
        println("Pacote para enviar ao servidor: "+ pedido)
        val pacote =
            DatagramPacket(pedido.toByteArray(), pedido.length, InetAddress.getByName(ipServidor), portaServidor)
        try {
            areaLog.appendText("Enviando pedido ALTERAR_ITEM para ID: $id com novo status: $novoStatus para o servidor em $ipServidor:$portaServidor...\n")
            socket?.send(pacote)

            val buffer = ByteArray(1024)
            val pacoteResposta = DatagramPacket(buffer, buffer.size)
            socket?.receive(pacoteResposta)
            areaLog.appendText("Resposta recebida do servidor após alteração do status.\n")

            val dados = String(pacoteResposta.data, 0, pacoteResposta.length, StandardCharsets.UTF_8)
            println("Dados recebidos do servidor: $dados")

            // Convertendo o JSON recebido em um JSONObject
            val jsonObject = JSONObject(dados)
            val jsonArray = jsonObject.getJSONArray("equipments")
            val listaEquipamentos = mutableListOf<Equipment>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val id = item.getInt("id")
                val name = item.getString("name")
                val status = item.getBoolean("status")
                listaEquipamentos.add(Equipment(id, name, status))
            }
            atualizarInterfaceListaEquipamentos(listaEquipamentos)
        } catch (e: Exception) {
            registrarErro("Erro ao alterar status do equipamento: ${e.message}")
        }
    }

    private fun atualizarInterfaceListaEquipamentos(equipamentos: List<Equipment>) {
        containerListaEquipamentos.children.clear()
        equipamentos.forEach { equipamento ->
            val statusCor = if (equipamento.status) "green" else "red"
            val statusTexto = if (equipamento.status) "Ativo" else "Inativo"

            // Configurando o layout do item com espaçamento e texto em negrito
            val hbox = HBox(10.0).apply {
                spacing = 10.0
                padding = Insets(5.0)

                // Label com o texto em negrito e estilizado
                val itemLabel = Label().apply {
                    text = "${equipamento.id}: ${equipamento.name} - $statusTexto"
                    font = Font.font("Arial", FontWeight.BOLD, 14.0)
                    style = "-fx-text-fill: white;"
                }

                // Botão para alterar o status
                val botaoAlterar = Button("Alterar").apply {
                    setOnAction {
                        val novoStatus = !equipamento.status
                        alterarStatusEquipamento(equipamento.id, novoStatus)
                    }
                }

                // Botão para carregar o equipamento
                val botaoCarregar = Button("Carregar").apply {
                    setOnAction {
                        buscarDetalhesEquipamento(equipamento.id)
                    }
                }

                // Adicionando o itemLabel ao botão com o fundo colorido
                val itemButton = Button().apply {
                    style = "-fx-background-color: $statusCor; -fx-padding: 10px; -fx-border-color: black; -fx-border-width: 1px;"
                    graphic = itemLabel
                    maxWidth = Double.MAX_VALUE
                }

                children.addAll(itemButton, botaoAlterar, botaoCarregar)
            }
            containerListaEquipamentos.children.add(hbox)
        }
    }


    private fun registrarErro(mensagem: String) {
        areaLog.appendText("ERRO: $mensagem\n")
        println("ERRO: $mensagem")
    }
}

data class Equipment(val id: Int, val name: String, val status: Boolean)

fun main() {
    Application.launch(ClienteUdp::class.java)
}