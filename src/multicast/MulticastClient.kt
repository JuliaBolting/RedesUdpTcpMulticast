package multicast

import javafx.application.Application
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.stage.Stage
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread
import com.google.gson.Gson

data class Mensagem(val data: String, val hora: String, val usuario: String, val mensagem: String)

class ClienteMulticast : Application() {

    private lateinit var socketMulticast: MulticastSocket
    private lateinit var endereco: InetAddress
    private var porta: Int = 0
    private var conectado: Boolean = false
    private var threadListening: Thread? = null

    override fun start(stagePrincipal: Stage) {
        val grade = GridPane()
        grade.padding = Insets(10.0)
        grade.hgap = 10.0
        grade.vgap = 10.0

        val rotuloUsuario = Label("Usuário:")
        val campoUsuario = TextField()
        grade.add(rotuloUsuario, 0, 0)
        grade.add(campoUsuario, 1, 0)

        val rotuloPorta = Label("Porta:")
        val campoPorta = TextField()
        grade.add(rotuloPorta, 0, 1)
        grade.add(campoPorta, 1, 1)

        val botaoIniciar = Button("Iniciar")
        grade.add(botaoIniciar, 1, 2)

        val campoMensagem = TextField()
        campoMensagem.promptText = "Digite uma mensagem"
        campoMensagem.isDisable = true
        grade.add(campoMensagem, 0, 3, 2, 1)

        val botaoEnviar = Button("Enviar")
        botaoEnviar.isDisable = true
        grade.add(botaoEnviar, 1, 4)

        botaoIniciar.setOnAction {
            if (conectado) {
                pararConexao()
                botaoIniciar.text = "Iniciar"
                campoMensagem.isDisable = true
                botaoEnviar.isDisable = true
            } else {
                iniciarConexao(campoPorta.text.toInt())
                botaoIniciar.text = "Parar"
                campoMensagem.isDisable = false
                botaoEnviar.isDisable = false
            }
        }

        botaoEnviar.setOnAction {
            val usuario = campoUsuario.text
            val mensagem = campoMensagem.text
            val dataHoraAtual = java.time.LocalDateTime.now()
            val data = dataHoraAtual.toLocalDate().toString()
            val hora = dataHoraAtual.toLocalTime().toString()
            enviarMensagem(data, hora, usuario, mensagem)
        }

        stagePrincipal.title = "Cliente UDP Multicast"
        stagePrincipal.scene = Scene(grade, 400.0, 300.0)
        stagePrincipal.setOnCloseRequest {
            fecharConexao()
        }
        stagePrincipal.show()
    }

    private fun iniciarConexao(porta: Int) {
        try {
            println("Iniciando conexão com o multicast...")
            this.porta = porta
            endereco = InetAddress.getByName("224.0.0.1")
            socketMulticast = MulticastSocket(porta)
            socketMulticast.joinGroup(endereco)

            conectado = true
            println("Conexão estabelecida com sucesso.")

            threadListening = thread {
                ouvirMensagens()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("Erro ao iniciar a conexão: ${e.message}")
        }
    }

    private fun pararConexao() {
        try {
            println("Parando conexão com o multicast...")
            socketMulticast.leaveGroup(endereco)
            socketMulticast.close()
            conectado = false
            println("Conexão encerrada com sucesso.")
        } catch (e: Exception) {
            e.printStackTrace()
            println("Erro ao parar a conexão: ${e.message}")
        }
    }

    private fun ouvirMensagens() {
        val buffer = ByteArray(1024)
        while (conectado) {
            try {
                val pacote = DatagramPacket(buffer, buffer.size)
                socketMulticast.receive(pacote)
                val mensagemJson = String(pacote.data, 0, pacote.length)
                val mensagem = Gson().fromJson(mensagemJson, Mensagem::class.java)
                println("Mensagem recebida de ${mensagem.usuario} em ${mensagem.data} ${mensagem.hora}: ${mensagem.mensagem}")
            } catch (e: Exception) {
                if (conectado) {
                    e.printStackTrace()
                    println("Erro ao ouvir mensagens: ${e.message}")
                }
                break
            }
        }
    }

    private fun enviarMensagem(data: String, hora: String, usuario: String, mensagem: String) {
        try {
            val mensagemJson = Gson().toJson(Mensagem(data, hora, usuario, mensagem))
            val buffer = mensagemJson.toByteArray()
            val pacote = DatagramPacket(buffer, buffer.size, endereco, porta)
            socketMulticast.send(pacote)
            println("Mensagem enviada: $mensagemJson")
        } catch (e: Exception) {
            e.printStackTrace()
            println("Erro ao enviar mensagem: ${e.message}")
        }
    }

    private fun fecharConexao() {
        println("Fechando todas as conexões...")
        if (conectado) {
            pararConexao()
        }
        threadListening?.interrupt()
        socketMulticast.close()
        println("Todas as conexões fechadas com sucesso.")
    }
}

fun main() {
    Application.launch(ClienteMulticast::class.java)
}